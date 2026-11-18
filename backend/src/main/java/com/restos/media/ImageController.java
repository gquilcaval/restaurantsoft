package com.restos.media;

import com.restos.shared.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Fotos de productos y logotipos guardadas en disco. El nombre es aleatorio y el tipo se valida por su
 * contenido real, no por la extensión enviada por el navegador.
 */
@RestController
public class ImageController {
    private static final Pattern FILE_NAME = Pattern.compile("[0-9a-f-]{36}\\.(jpg|png|webp)");
    private final Path directory;

    public ImageController(@Value("${restos.uploads-dir:./uploads}") String directory) throws IOException {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    @PostMapping("/api/admin/uploads/images")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    Uploaded upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Selecciona una imagen");
        String extension;
        try (InputStream input = file.getInputStream()) {
            extension = detectExtension(input.readNBytes(12));
        }
        if (extension == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Solo se aceptan imágenes JPG, PNG o WebP");
        }
        var name = UUID.randomUUID() + "." + extension;
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        return new Uploaded("/api/public/images/" + name);
    }

    @GetMapping("/api/public/images/{name}")
    ResponseEntity<Resource> image(@PathVariable String name) {
        if (!FILE_NAME.matcher(name).matches()) throw new ApiException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
        var file = directory.resolve(name).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Imagen no encontrada");
        }
        var type = name.endsWith(".png") ? MediaType.IMAGE_PNG
                : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        // El nombre nunca se reutiliza, así que el navegador puede guardarla en caché indefinidamente.
        return ResponseEntity.ok().contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    private String detectExtension(byte[] header) {
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (header.length >= 8 && Arrays.equals(Arrays.copyOf(header, 8),
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) {
            return "png";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return null;
    }

    public record Uploaded(String url) {}
}
