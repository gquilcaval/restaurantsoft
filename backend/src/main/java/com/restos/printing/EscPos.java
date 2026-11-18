package com.restos.printing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.text.Normalizer;

/**
 * Convierte el texto de un ticket (ver {@link Ticket}) en bytes ESC/POS, el lenguaje de las ticketeras
 * térmicas (Epson, Xprinter, 3nStar, Bixolon y compatibles). Usa la página de códigos PC850 para tildes y «ñ».
 */
final class EscPos {
    private static final byte ESC = 0x1B, GS = 0x1D, LF = 0x0A;
    private static final Charset PC850 = charset();

    private EscPos() {}

    static byte[] render(String content, int paperWidth) {
        var columns = Ticket.columns(paperWidth);
        var out = new ByteArrayOutputStream();
        write(out, ESC, '@');            // reinicia la impresora
        write(out, ESC, 't', 2);         // página de códigos PC850 (español)
        for (var line : content.split("\n", -1)) {
            if (line.startsWith("#BIG ")) {
                write(out, ESC, 'a', 1, GS, '!', 0x11, ESC, 'E', 1);
                text(out, line.substring(5));
                write(out, GS, '!', 0, ESC, 'E', 0, ESC, 'a', 0);
            } else if (line.startsWith("#B ")) {
                write(out, ESC, 'E', 1);
                text(out, line.substring(3));
                write(out, ESC, 'E', 0);
            } else if (line.startsWith("#C ")) {
                write(out, ESC, 'a', 1);
                text(out, line.substring(3));
                write(out, ESC, 'a', 0);
            } else if (line.equals("#HR")) {
                text(out, "-".repeat(columns));
            } else {
                text(out, line);
            }
        }
        write(out, ESC, 'd', 4);          // avanza el papel
        write(out, GS, 'V', 66, 0);       // corte parcial
        return out.toByteArray();
    }

    private static void text(ByteArrayOutputStream out, String text) {
        var bytes = PC850 != null ? text.getBytes(PC850) : ascii(text).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(bytes);
        out.write(LF);
    }

    private static void write(ByteArrayOutputStream out, int... values) {
        for (var value : values) out.write(value);
    }

    /** Si el servidor no tuviera PC850, se imprimen las letras sin tilde antes que símbolos raros. */
    private static String ascii(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static Charset charset() {
        try { return Charset.forName("IBM850"); } catch (RuntimeException unavailable) { return null; }
    }
}
