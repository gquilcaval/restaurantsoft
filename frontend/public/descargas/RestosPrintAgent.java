import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Programa puente de impresión de RestOS. Va en una PC del local (la de caja, por ejemplo), en la misma red
 * que las ticketeras. Pide a RestOS las comandas pendientes y las envía a cada impresora (puerto 9100, ESC/POS).
 *
 * Primera vez:  java RestosPrintAgent.java vincular http://IP-DE-RESTOS:8081 123456
 * Luego:        java RestosPrintAgent.java
 *
 * Requiere Java 17 o superior. No necesita nada más.
 */
public class RestosPrintAgent {
    static final String VERSION = "1.0";
    static final Path CONFIG = Path.of(System.getProperty("user.home"), ".restos-print-agent.properties");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equalsIgnoreCase("vincular")) {
            pair(args[1], args[2]);
            return;
        }
        var config = load();
        if (config == null) {
            System.out.println("Esta PC aún no está vinculada.");
            System.out.println("En RestOS: Configuración → Impresoras → Vincular una PC. Luego ejecuta:");
            System.out.println("  java RestosPrintAgent.java vincular http://IP-DE-RESTOS:8081 CODIGO");
            System.exit(1);
        }
        run(config.getProperty("server"), config.getProperty("token"));
    }

    static void pair(String server, String code) throws Exception {
        var base = server.replaceAll("/+$", "");
        var response = post(base + "/api/public/print-agent/pair", null, "{\"code\":\"" + code.trim() + "\",\"version\":\"" + VERSION + "\"}");
        if (response.statusCode() != 200) {
            System.out.println("No se pudo vincular: " + message(response.body()));
            System.exit(1);
        }
        var body = (Map<?, ?>) Json.parse(response.body());
        var properties = new Properties();
        properties.setProperty("server", base);
        properties.setProperty("token", (String) body.get("token"));
        try (var out = Files.newOutputStream(CONFIG)) {
            properties.store(out, "RestOS - programa de impresión");
        }
        log("Vinculada con " + body.get("branchName") + ". Ahora ejecuta: java RestosPrintAgent.java");
    }

    static void run(String server, String token) throws InterruptedException {
        log("Programa de impresión de RestOS " + VERSION + " conectado a " + server);
        log("Deja esta ventana abierta. Para cerrar: Ctrl+C.");
        var wait = 2000L;
        var offline = false;
        while (true) {
            try {
                var response = post(server + "/api/public/print-agent/poll", token, "{\"version\":\"" + VERSION + "\"}");
                if (response.statusCode() == 401) {
                    log("Esta PC ya no está vinculada a RestOS (" + message(response.body()) + "). Vuelve a vincularla.");
                    System.exit(2);
                }
                if (response.statusCode() != 200) throw new IOException("RestOS respondió " + response.statusCode());
                if (offline) log("Conexión con RestOS recuperada.");
                offline = false;
                wait = 2000L;
                for (var item : (List<?>) Json.parse(response.body())) {
                    var job = (Map<?, ?>) item;
                    var error = print((String) job.get("host"), ((Number) job.get("port")).intValue(), Base64.getDecoder().decode((String) job.get("data")));
                    var id = (String) job.get("id");
                    post(server + "/api/public/print-agent/jobs/" + id + "/result", token,
                            error == null ? "{\"ok\":true}" : "{\"ok\":false,\"error\":" + Json.quote(error) + "}");
                    log((error == null ? "Impreso: " : "ERROR: ") + job.get("title") + (error == null ? "" : " → " + error));
                }
            } catch (IOException | RuntimeException problem) {
                if (!offline) log("Sin conexión con RestOS (" + problem.getMessage() + "). Reintentando…");
                offline = true;
                wait = Math.min(wait * 2, 15000L);
            }
            Thread.sleep(wait);
        }
    }

    /** Envía los bytes a la ticketera por la red. Devuelve null si salió bien, o el error en palabras simples. */
    static String print(String host, int port, byte[] data) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            out.write(data);
            out.flush();
            socket.shutdownOutput();
            return null;
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.NoRouteToHostException refused) {
            return "La impresora " + host + " no responde (¿apagada o desconectada de la red?)";
        } catch (java.net.UnknownHostException unknown) {
            return "No se encontró la impresora " + host;
        } catch (IOException problem) {
            return "Error con la impresora " + host + ": " + problem.getMessage();
        }
    }

    static HttpResponse<String> post(String url, String token, String json) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) builder.header("X-Agent-Token", token);
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrumpido");
        }
    }

    static Properties load() throws IOException {
        if (!Files.exists(CONFIG)) return null;
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(CONFIG)) {
            properties.load(in);
        }
        return properties.getProperty("server") == null || properties.getProperty("token") == null ? null : properties;
    }

    static String message(String body) {
        try {
            var parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> map && map.get("message") != null) return String.valueOf(map.get("message"));
        } catch (RuntimeException ignored) { /* respuesta sin JSON */ }
        return body == null || body.isBlank() ? "sin respuesta" : body;
    }

    static void log(String text) {
        System.out.println("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + text);
    }

    /** JSON mínimo (objetos, listas, textos, números, true/false/null): lo justo para hablar con RestOS. */
    static final class Json {
        private final String text;
        private int at;

        private Json(String text) { this.text = text; }

        static Object parse(String text) {
            var parser = new Json(text);
            var value = parser.value();
            parser.space();
            return value;
        }

        static String quote(String value) {
            var out = new StringBuilder("\"");
            for (var c : value.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }

        private Object value() {
            space();
            var c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (text.startsWith("true", at)) { at += 4; return true; }
            if (text.startsWith("false", at)) { at += 5; return false; }
            if (text.startsWith("null", at)) { at += 4; return null; }
            var start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            return Double.parseDouble(text.substring(start, at));
        }

        private Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            at++;
            space();
            if (text.charAt(at) == '}') { at++; return map; }
            while (true) {
                space();
                var key = string();
                space();
                at++; // :
                map.put(key, value());
                space();
                if (text.charAt(at++) == '}') return map;
            }
        }

        private List<Object> array() {
            var list = new ArrayList<Object>();
            at++;
            space();
            if (text.charAt(at) == ']') { at++; return list; }
            while (true) {
                list.add(value());
                space();
                if (text.charAt(at++) == ']') return list;
            }
        }

        private String string() {
            var out = new StringBuilder();
            at++;
            while (true) {
                var c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                var e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); at += 4; }
                    default -> out.append(e);
                }
            }
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }
    }
}
