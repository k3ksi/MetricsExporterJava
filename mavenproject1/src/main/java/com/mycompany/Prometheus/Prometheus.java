/*
Exporter di metriche in Java per prometheus

L'exporter legge da un file di log e invia le metriche al server prometheus in locale sulla porta 8001
I log sono nel formato :
STATUS TIME PROCESS MESSAGE
Dove STATUS ha possibili valori 'errore' o 'default'
TIME indica una stringa del tipo OffsetTime con Offset del tipo +0200 e non +02:00
PROCESS indica da quale processo/gruppo proviene il log
MESSAGE indica il contenuto del messaggio
*/
package com.mycompany.Prometheus;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.time.OffsetTime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.Summary;
import io.prometheus.client.exporter.common.TextFormat;

public class Prometheus {

  private static final String QUERY_PARAM_SEPERATOR = "&";
  private static final String NAMESPACE_JAVA_APP = "logs_java_app";
  private static final String NAMESPACE_KERNEL = "kernel_errors";
  private static final String NAMESPACE_USER = "user_errors";
  private static final String UTF_8 = "UTF-8";
  static int lastPosition = 0;
  static int kernel_errors = 0;
  static int user_errors = 0;
  static OffsetTime endTime = null;
  static OffsetTime startTime = null;
  static BufferedReader reader;
  private static class LocalByteArray extends ThreadLocal < ByteArrayOutputStream > {
    @Override
    protected ByteArrayOutputStream initialValue() {
      return new ByteArrayOutputStream(1 << 20);
    }
  }

  public static void main(String[] args) throws Exception {

    CollectorRegistry registry = CollectorRegistry.defaultRegistry;
    ArrayList < LogLine > logList = new ArrayList < > ();
    readFile_SetTime(logList);
    System.out.println("Errori totali : " + countTotalErrors(logList));
    startMetricsPopulatorThread(registry, logList);
    startHttpServer(registry);
    System.out.println("Started");
  }

  private static void startMetricsPopulatorThread(CollectorRegistry registry, ArrayList < LogLine > logList) {
    Counter counter_total = counter(registry, NAMESPACE_JAVA_APP);
    Counter counter_kernel = counter(registry, NAMESPACE_KERNEL);
    Counter counter_user = counter(registry, NAMESPACE_USER);
    Gauge gauge_total = gauge(registry, NAMESPACE_JAVA_APP);
    Gauge gauge_kernel = gauge(registry, NAMESPACE_KERNEL);
    Gauge gauge_user = gauge(registry, NAMESPACE_USER);
    //Histogram histogram = histogram();
    //Summary summary = summary(registry);

    Thread bgThread = new Thread(() -> {
      while (true) {
        try {
          if (endTime != null) {
            int errors = countErrorsCycle(logList);
            System.out.println(errors);
            counter_total.inc(errors);
            counter_kernel.inc(kernel_errors);
            counter_user.inc(user_errors);
            gauge_total.set(errors);
            gauge_kernel.set(kernel_errors);
            gauge_user.set(user_errors);
          }
          TimeUnit.SECONDS.sleep(14);
          readFile_SetTime(logList);

          //histogram.observe(errors);
          //summary.observe(errors);
        } catch (InterruptedException e) {
          System.err.println("Error: " + e.getMessage());
        }
      }
    });
    bgThread.start();
  }

  private static Summary summary(CollectorRegistry registry) {
    return Summary.build()
      .namespace(NAMESPACE_JAVA_APP)
      .name("s")
      .help("s summary")
      .register(registry);
  }

  private static Histogram histogram() {
    return Histogram.build()
      .namespace(NAMESPACE_JAVA_APP)
      .name("h")
      .help("h help")
      .register();
  }

  private static Gauge gauge(CollectorRegistry registry, String namespace) {
    return Gauge.build()
      .namespace(namespace)
      .name("g")
      .help("g healp")
      .register(registry);
  }

  private static Counter counter(CollectorRegistry registry, String namespace) {
    return Counter.build()
      .namespace(namespace)
      .name("a")
      .help("a help")
      .register(registry);
  }

  private static void startHttpServer(CollectorRegistry registry) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
    HTTPMetricHandler mHandler = new HTTPMetricHandler(registry);
    addContext(server, mHandler);
    server.setExecutor(null); // creates a default executor
    server.start();
  }

  private static void addContext(HttpServer server, HTTPMetricHandler mHandler) {
    server.createContext("/", mHandler);
    server.createContext("/metrics", mHandler);
    server.createContext("/healthy", mHandler);
  }

  static class HTTPMetricHandler implements HttpHandler {

    private final static String HEALTHY_RESPONSE = "Exporter is Healthy.";

    private final CollectorRegistry registry;
    private final LocalByteArray response = new LocalByteArray();

    HTTPMetricHandler(CollectorRegistry registry) {
      this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String query = exchange.getRequestURI().getRawQuery();
      String contextPath = exchange.getHttpContext().getPath();

      ByteArrayOutputStream outPutStream = outputStream();

      writeToStream(query, contextPath, outPutStream);
      writeHeaders(exchange);

      gzipStream(exchange, outPutStream);

      exchange.close();
      System.out.println("Handled :" + contextPath);
    }

    private void gzipStream(HttpExchange exchange, ByteArrayOutputStream outPutStream) throws IOException {
      final GZIPOutputStream os = new GZIPOutputStream(exchange.getResponseBody());

      try {
        outPutStream.writeTo(os);
      } finally {
        os.close();
      }
    }

    private void writeHeaders(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().set("Content-Type", TextFormat.CONTENT_TYPE_004);
      exchange.getResponseHeaders().set("Content-Encoding", "gzip");
      exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
    }

    private void writeToStream(String query, String contextPath, ByteArrayOutputStream outPutStream) throws IOException {
      OutputStreamWriter osw = new OutputStreamWriter(outPutStream, Charset.forName(UTF_8));
      if ("/-/healthy".equals(contextPath)) {
        osw.write(HEALTHY_RESPONSE);
      } else {
        TextFormat.write004(osw, registry.filteredMetricFamilySamples(parseQuery(query)));
      }
      osw.close();
    }

    private ByteArrayOutputStream outputStream() {
      ByteArrayOutputStream response = this.response.get();
      response.reset();
      return response;
    }
  }

  private static Set < String > parseQuery(String query) throws IOException {
    Set < String > names = new HashSet < String > ();
    if (query != null) {
      String[] pairs = query.split(QUERY_PARAM_SEPERATOR);
      for (String pair: pairs) {
        int idx = pair.indexOf("=");
        if (idx != -1 && URLDecoder.decode(pair.substring(0, idx), UTF_8).equals("name[]")) {
          names.add(URLDecoder.decode(pair.substring(idx + 1), UTF_8));
        }
      }
    }
    return names;
  }

  public static int countTotalErrors(ArrayList < LogLine > logList) {
    int errors = 0;
    for (LogLine l: logList) {
      if (l.isError()) {
        errors++;
      }
    }
    return errors;
  }

  public static int countErrorsCycle(ArrayList < LogLine > logList) {
    int errors = 0;
    kernel_errors = 0;
    user_errors = 0;
    if (startTime == null) {
      startTime = logList.get(0).getTime();
    }

    for (LogLine l: logList) {
      if (l.getTime().compareTo(startTime) >= 0 && l.getTime().compareTo(endTime) < 0) {
        if (l.isError()) {
          errors++;
          if(l.getProcess().equals("kernel")){
              kernel_errors++;
          }
        }
        lastPosition++;
      }
    }
    user_errors = errors - kernel_errors;
    startTime = endTime;
    return errors;
  }

  public static void readFile_SetTime(ArrayList < LogLine > logList) {
    /*
      N.B. L'orario nel file da cui si legge deve essere una stringa nel formato hh:mm:ss.1234567+0200
      L'Offset deve essere senza i :, quindi ad esempio +0200 e non +02:00.
     */
    try {
      reader = new BufferedReader(new FileReader("/Users/andrea/NetBeansProjects/mavenproject1/src/main/java/com/mycompany/Prometheus/system.log"));
      String strLine;
      int i = 0;
      while ((strLine = reader.readLine()) != null) {
        String message = "";
        String[] tokens = strLine.split("\\s+"); //splitto la riga di log letta

        for (int j = 3; j < tokens.length; j++) {
          message += tokens[j] + " "; //costruisco la stringa di messaggio
        }

        if (tokens[0].equals("errore") || tokens[0].equals("default")) {
          tokens[1] = tokens[1].substring(0, tokens[1].length() - 2) + ":00"; //sistemo l'orario letto
          if (startTime == null || OffsetTime.parse(tokens[1]).compareTo(startTime) > 0) {
            logList.add(new LogLine(tokens[0], tokens[1], tokens[2], message.trim()));
            //logList.get(i).printLogLine(i);
            i++;
          }
        }
      }
      reader.close();
      if (startTime == null) {
        startTime = logList.get(logList.size() - 1).getTime();
      } else {
        endTime = logList.get(logList.size() - 1).getTime();
      }

    } catch (IOException e) {
      System.err.println("Error: " + e.getMessage());
    }
  }
}