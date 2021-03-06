package com.lightstep.opentelemetry.exporter;

import com.lightstep.tracer.grpc.Auth;
import com.lightstep.tracer.grpc.KeyValue;
import com.lightstep.tracer.grpc.ReportRequest;
import com.lightstep.tracer.grpc.ReportResponse;
import com.lightstep.tracer.grpc.Reporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpansProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Exports spans to Lightstep via OkHttp, using Lightstep's protobuf model.
 */
@ThreadSafe
public class LightstepSpanExporter implements SpanExporter {
  static final String MEDIA_TYPE_STRING = "application/octet-stream";
  static final String LIGHTSTEP_ACCESS_TOKEN = "Lightstep-Access-Token";
  static final String DEFAULT_HOST = "collector-grpc.lightstep.com";
  static final String PATH = "/api/v2/reports";
  private static final Logger logger = Logger.getLogger(LightstepSpanExporter.class.getName());
  @Nullable
  private static final MediaType MEDIA_TYPE = MediaType.parse(MEDIA_TYPE_STRING);
  /**
   * Default collector port for HTTPS.
   */
  private static final int DEFAULT_SECURE_PORT = 443;

  /**
   * Default collector port for HTTP.
   */
  private static final int DEFAULT_PLAINTEXT_PORT = 80;

  private static final String PROTOCOL_HTTPS = "https";
  private static final String PROTOCOL_HTTP = "http";
  private static final String GUID_KEY = "lightstep.guid";
  private static final String COMPONENT_NAME_KEY = "lightstep.component_name";
  private static final String LIGHTSTEP_TRACER_PLATFORM_KEY = "lightstep.tracer_platform";
  private static final String LIGHTSTEP_TRACER_PLATFORM_VERSION_KEY =
      "lightstep.tracer_platform_version";

  /**
   * Default duration the tracer should wait for a response from the collector when sending a
   * report.
   */
  private static final long DEFAULT_DEADLINE_MILLIS = 30000;

  /**
   * Java System property that will be used as the component name when no other value is provided.
   */
  private static final String COMPONENT_NAME_SYSTEM_PROPERTY_KEY = "sun.java.command";
  /**
   * Thread-specific random number generators. Each is seeded with the thread ID, so the sequence of
   * pseudo-random numbers are unique between threads.
   *
   * <p>See http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
   */
  private static final ThreadLocal<Random> random =
      new ThreadLocal<Random>() {
        @Override
        protected Random initialValue() {
          // It'd be nice to get the process ID into the mix, but there's no clear
          // cross-platform, Java 6-compatible way to determine that
          return new Random(
              System.currentTimeMillis()
                  * (System.nanoTime() % 1000000)
                  * Thread.currentThread().getId()
                  * (long) (1024 * Math.random()));
        }
      };
  private final URL collectorUrl;
  private final OkHttpClient client;
  private final Auth.Builder auth;
  private final String componentName;

  /**
   * Creates a new Lightstep OkHttp Span Reporter.
   *
   * @param collectorUrl collector url.
   * @param deadlineMillis The maximum amount of time the tracer should wait for a response from the
   * collector when sending a report.
   * @param accessToken Your specific token for Lightstep access.
   * @param okHttpDns DNS service used to lookup IP addresses for hostnames
   * @param componentName The component name attribute. If not set, will default to the Java runtime
   * command.
   */
  private LightstepSpanExporter(
      URL collectorUrl,
      long deadlineMillis,
      String accessToken,
      OkHttpDns okHttpDns,
      String componentName) {
    this.collectorUrl = collectorUrl;
    this.componentName = componentName;
    OkHttpClient.Builder builder =
        new OkHttpClient.Builder().connectTimeout(deadlineMillis, TimeUnit.MILLISECONDS);

    if (okHttpDns != null) {
      builder.dns(new CustomDns(okHttpDns));
    }

    this.client = builder.build();
    this.auth = Auth.newBuilder().setAccessToken(accessToken);
  }

  private static long generateRandomGuid() {
    // Note that ThreadLocalRandom is a singleton, thread safe Random Generator
    return random.get().nextLong();
  }

  /**
   * Creates a new builder instance.
   *
   * @return a new instance builder for this exporter
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Submits all the given spans in a single batch to the Lightstep collector.
   *
   * @param spans the list of sampled Spans to be exported.
   * @return the result of the operation
   */
  @Override
  public ResultCode export(Collection<SpanData> spans) {
    final long guid = generateRandomGuid();

    ReportRequest request =
        ReportRequest.newBuilder()
            .setAuth(auth)
            .setReporter(
                Reporter.newBuilder()
                    .setReporterId(guid)
                    .addTags(
                        KeyValue.newBuilder()
                            .setStringValue(componentName)
                            .setKey(COMPONENT_NAME_KEY)
                            .build())
                    .addTags(KeyValue.newBuilder().setKey(GUID_KEY).setIntValue(guid).build())
                    .addTags(
                        KeyValue.newBuilder()
                            .setKey(LIGHTSTEP_TRACER_PLATFORM_KEY)
                            .setStringValue("jre")
                            .build())
                    .addTags(
                        KeyValue.newBuilder()
                            .setKey(LIGHTSTEP_TRACER_PLATFORM_VERSION_KEY)
                            .setStringValue(System.getProperty("java.version"))
                            .build())
                    .build())
            .addAllSpans(Adapter.toLightstepSpans(spans))
            .build();

    try (Response response = client.newCall(toRequest(request)).execute()) {
      if (!response.isSuccessful()) {
        logger.log(Level.WARNING, "Failed to post spans to collector. " + response.toString());
        return ResultCode.FAILED_NOT_RETRYABLE;
      }

      final ResponseBody body = response.body();
      if (body == null) {
        logger.log(Level.WARNING, "Response body is null");
        return ResultCode.FAILED_NOT_RETRYABLE;
      }

      final ReportResponse reportResponse = ReportResponse.parseFrom(body.byteStream());
      if (!reportResponse.getErrorsList().isEmpty()) {
        List<String> errs = reportResponse.getErrorsList();
        for (String err : errs) {
          logger.log(Level.WARNING, "Collector response contained error: " + err);
        }
        return ResultCode.FAILED_NOT_RETRYABLE;
      }

      return ResultCode.SUCCESS;
    } catch (Throwable e) {
      logger.log(Level.WARNING, "Failed to post spans", e);
      return ResultCode.FAILED_NOT_RETRYABLE;
    }
  }

  private Request toRequest(ReportRequest request) {
    return new Request.Builder()
        .url(this.collectorUrl)
        .post(RequestBody.create(MEDIA_TYPE, request.toByteArray()))
        .addHeader(LIGHTSTEP_ACCESS_TOKEN, request.getAuth().getAccessToken())
        .build();
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled.
   */
  @Override
  public void shutdown() {
    client.dispatcher().executorService().shutdown();
  }

  public interface OkHttpDns {
    List<InetAddress> lookup(String hostname);
  }

  static class CustomDns implements Dns {
    final OkHttpDns dns;

    public CustomDns(OkHttpDns dns) {
      this.dns = dns;
    }

    @Override
    public List<InetAddress> lookup(String hostname) {
      return dns.lookup(hostname);
    }
  }

  /**
   * Builder utility for this exporter.
   */
  public static class Builder {
    private int collectorPort = -1;
    private String collectorProtocol = PROTOCOL_HTTPS;
    private String collectorHost = DEFAULT_HOST;
    private long deadlineMillis = DEFAULT_DEADLINE_MILLIS;
    private String accessToken = "";
    private OkHttpDns okHttpDns;
    private String componentName;

    /**
     * Creates builder from system properties and environmental variables.
     *
     * @return this builder's instance
     */
    public static Builder fromEnv() {
      Builder builder = new Builder();
      builder.collectorProtocol = getProperty("LIGHTSTEP_COLLECTOR_PROTOCOL", PROTOCOL_HTTPS);
      builder.collectorPort = Integer
          .parseInt(getProperty("LIGHTSTEP_COLLECTOR_PORT", String.valueOf(DEFAULT_SECURE_PORT)));
      builder.collectorHost = getProperty("LIGHTSTEP_COLLECTOR_HOST", DEFAULT_HOST);
      builder.deadlineMillis = Long
          .parseLong(
              getProperty("LIGHTSTEP_DEADLINE_MILLIS", String.valueOf(DEFAULT_DEADLINE_MILLIS)));
      builder.componentName = getProperty("LIGHTSTEP_COMPONENT_NAME", defaultComponentName());
      builder.accessToken = getProperty("LIGHTSTEP_ACCESS_TOKEN", "");
      return builder;
    }

    /**
     * Sets the host to which the tracer will send data. If not set, will default to the primary
     * Lightstep collector address.
     *
     * @param collectorHost The hostname for the Lightstep collector.
     * @return this builder's instance
     * @throws IllegalArgumentException If the collectorHost argument is invalid.
     */
    public Builder setCollectorHost(String collectorHost) {
      if (collectorHost == null || "".equals(collectorHost.trim())) {
        throw new IllegalArgumentException("Invalid collector host: " + collectorHost);
      }
      this.collectorHost = collectorHost;
      return this;
    }

    /**
     * Sets the port to which the tracer will send data. If not set, will default to {@code
     * DEFAULT_SECURE_PORT} when the protocol is https and {@code DEFAULT_PLAINTEXT_PORT} when the
     * protocol is http.
     *
     * @param collectorPort The port for the Lightstep collector.
     * @return this builder's instance
     * @throws IllegalArgumentException If the collectorPort is invalid.
     */
    public Builder setCollectorPort(int collectorPort) {
      if (collectorPort <= 0) {
        throw new IllegalArgumentException("Invalid collector port: " + collectorPort);
      }
      this.collectorPort = collectorPort;
      return this;
    }

    /**
     * Sets the protocol which will be used when sending data to the tracer.
     *
     * @param protocol Either 'http' or 'https'
     * @return this builder's instance
     * @throws IllegalArgumentException If the protocol argument is invalid.
     */
    public Builder setCollectorProtocol(String protocol) {
      if (!PROTOCOL_HTTPS.equals(protocol) && !PROTOCOL_HTTP.equals(protocol)) {
        throw new IllegalArgumentException("Invalid protocol for collector: " + protocol);
      }
      this.collectorProtocol = protocol;
      return this;
    }

    /**
     * Overrides the default deadlineMillis with the provided value.
     *
     * @param deadlineMillis The maximum amount of time the tracer should wait for a response from
     * the collector when sending a report.
     * @return this builder's instance
     */
    public Builder setDeadlineMillis(long deadlineMillis) {
      this.deadlineMillis = deadlineMillis;
      return this;
    }

    /**
     * Sets the token for Lightstep access
     *
     * @param accessToken Your specific token for Lightstep access.
     * @return this builder's instance
     */
    public Builder setAccessToken(String accessToken) {
      this.accessToken = accessToken;
      return this;
    }

    /**
     * Sets the DNS service used to lookup IP addresses for hostnames when using the OkHttp
     * transport. If not set, the default DNS service used by OkHttp will be used.
     *
     * @param okHttpDns the Dns service object.
     * @return this builder's instance
     */
    public Builder setOkHttpDns(OkHttpDns okHttpDns) {
      if (okHttpDns == null) {
        throw new IllegalArgumentException("dns cannot be null");
      } else {
        this.okHttpDns = okHttpDns;
        return this;
      }
    }

    /**
     * Sets the component name attribute. If not set, will default to the Java runtime command.
     *
     * @param name The name of the component being traced.
     * @return this builder's instance
     */
    public Builder setComponentName(String name) {
      this.componentName = name;
      return this;
    }

    /**
     * If not set, provides a default value for the component name.
     */
    private void setDefaultComponentName() {
      if (componentName == null) {
        setComponentName(defaultComponentName());
      }
    }

    private static String defaultComponentName() {
      String componentNameSystemProperty = System.getProperty(COMPONENT_NAME_SYSTEM_PROPERTY_KEY);
      if (componentNameSystemProperty != null) {
        StringTokenizer st = new StringTokenizer(componentNameSystemProperty);
        if (st.hasMoreTokens()) {
          return st.nextToken();
        }
      }
      return null;
    }

    private void defaultDeadlineMillis() {
      if (deadlineMillis < 0) {
        deadlineMillis = DEFAULT_DEADLINE_MILLIS;
      }
    }

    private int getPort() {
      if (collectorPort > 0) {
        return collectorPort;
      } else if (collectorProtocol.equals(PROTOCOL_HTTPS)) {
        return DEFAULT_SECURE_PORT;
      } else {
        return DEFAULT_PLAINTEXT_PORT;
      }
    }

    private URL getCollectorUrl() throws MalformedURLException {
      int port = getPort();
      return new URL(collectorProtocol, collectorHost, port, PATH);
    }

    /**
     * Constructs a new instance of the exporter based on the builder's values.
     *
     * @return a new exporter's instance
     * @throws MalformedURLException if an unknown protocol or the port is a negative number
     */
    public LightstepSpanExporter build() throws MalformedURLException {
      defaultDeadlineMillis();
      setDefaultComponentName();
      return new LightstepSpanExporter(
          getCollectorUrl(), deadlineMillis, accessToken, okHttpDns, componentName);
    }

    /**
     * Installs exporter into tracer SDK provider with batching span processor.
     *
     * @param tracerSdkProvider tracer SDK provider
     */
    public void install(TracerSdkProvider tracerSdkProvider) throws MalformedURLException {
      BatchSpansProcessor spansProcessor = BatchSpansProcessor.newBuilder(this.build()).build();
      tracerSdkProvider.addSpanProcessor(spansProcessor);
    }

    /**
     * Installs exporter into tracer SDK default provider with batching span processor.
     */
    public void install() throws MalformedURLException {
      BatchSpansProcessor spansProcessor = BatchSpansProcessor.newBuilder(this.build()).build();
      OpenTelemetrySdk.getTracerProvider().addSpanProcessor(spansProcessor);
    }

    private static String getProperty(String name, String defaultValue) {
      String val = System.getProperty(name, System.getenv(name));
      if (val == null || val.isEmpty()) {
        return defaultValue;
      }
      return val;
    }
  }

}
