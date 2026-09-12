import com.sun.net.httpserver.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
public class Collector {
  public static void main(String[] a) throws Exception {
    HttpServer s = HttpServer.create(new java.net.InetSocketAddress(9999), 0);
    s.createContext("/", new HttpHandler(){
      public void handle(HttpExchange x) throws IOException {
        InputStream in = x.getRequestBody();
        if ("gzip".equals(x.getRequestHeaders().getFirst("Content-Encoding"))) in = new GZIPInputStream(in);
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        System.out.println("### COLLECTOR GOT " + x.getRequestURI() + " ###");
        System.out.println(b.toString("UTF-8"));
        x.sendResponseHeaders(200, -1); x.close();
      }});
    s.start(); Thread.sleep(12000); s.stop(0);
  }
}
