package com.enterprise.demo.controller;

import com.enterprise.demo.dto.FileDto;
import com.enterprise.demo.service.FileStorageService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Validated   // enables method-level constraint validation (e.g. @Positive on path vars)
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileDto> uploadFile(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileStorageService.uploadFile(file));
    }

    @GetMapping
    public ResponseEntity<List<FileDto>> listFiles() {
        return ResponseEntity.ok(fileStorageService.listFiles());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileDto> getFile(
            @PathVariable @Positive(message = "File ID must be a positive number") Long id) {
        return ResponseEntity.ok(fileStorageService.getFile(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable @Positive(message = "File ID must be a positive number") Long id) {
        fileStorageService.deleteFile(id);
        return ResponseEntity.noContent().build();
    }
}
