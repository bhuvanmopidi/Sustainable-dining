package com.sustainabledining.imports;

import com.sustainabledining.common.ApiException;
import com.sustainabledining.imports.ImportDtos.ImportResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * CSV upload, either as the raw request body (Content-Type text/csv) or as a form upload (multipart field "file").
 * Add ?dryRun=true to check a file without saving anything.
 */
@RestController
@RequestMapping("/api/semesters/{id}/import")
public class ImportController {
    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/menus", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public ImportResult menus(@PathVariable UUID id, @RequestBody(required = false) byte[] csv,
                              @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importMenus(id, utf8(csv), dryRun);
    }

    @PostMapping(value = "/menus", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult menusFile(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                  @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importMenus(id, read(file), dryRun);
    }

    @PostMapping(value = "/observations", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public ImportResult observations(@PathVariable UUID id, @RequestBody(required = false) byte[] csv,
                                     @RequestParam(defaultValue = "csv-import") String enteredBy,
                                     @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importObservations(id, utf8(csv), enteredBy, dryRun);
    }

    @PostMapping(value = "/observations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult observationsFile(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "csv-import") String enteredBy,
                                         @RequestParam(defaultValue = "false") boolean dryRun) {
        return imports.importObservations(id, read(file), enteredBy, dryRun);
    }

    /** Always UTF-8, whatever charset the client declared (Excel and PowerShell often declare none). */
    private static String utf8(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    private static String read(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.unprocessable("CSV_MALFORMED", "the uploaded file could not be read");
        }
    }
}