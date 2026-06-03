package com.enterprise.demo.service;

import com.enterprise.demo.config.S3Properties;
import com.enterprise.demo.dto.FileDto;
import com.enterprise.demo.entity.FileMetadata;
import com.enterprise.demo.exception.FileStorageException;
import com.enterprise.demo.exception.InvalidFileException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileMetadataRepository fileMetadataRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    public FileDto uploadFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("Cannot upload empty file");
        }

        String sanitizedName = sanitizeFilename(file.getOriginalFilename());
        String s3Key = "uploads/" + UUID.randomUUID() + "_" + sanitizedName;
        String contentType = (file.getContentType() != null && !file.getContentType().isBlank())
                ? file.getContentType()
                : "application/octet-stream";

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3Properties.getBucketName())
                            .key(s3Key)
                            .contentType(contentType)
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromBytes(file.getBytes()));
        } catch (SdkException e) {
            throw new FileStorageException("Failed to upload file to S3: " + sanitizedName, e);
        } catch (IOException e) {
            throw new FileStorageException("Failed to read file content: " + sanitizedName, e);
        }

        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : sanitizedName);
        metadata.setS3Key(s3Key);
        metadata.setS3Bucket(s3Properties.getBucketName());
        metadata.setContentType(contentType);
        metadata.setFileSize(file.getSize());
        metadata.setUploadedAt(Instant.now());

        return toDto(fileMetadataRepository.save(metadata), null);
    }

    public FileDto getFile(Long id) {
        FileMetadata metadata = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + id));

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(s3Properties.getPresignedUrlExpiryMinutes()))
                .getObjectRequest(r -> r
                        .bucket(metadata.getS3Bucket())
                        .key(metadata.getS3Key()))
                .build();

        String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();
        return toDto(metadata, presignedUrl);
    }

    public List<FileDto> listFiles() {
        return fileMetadataRepository.findAll().stream()
                .map(m -> toDto(m, null))
                .toList();
    }

    public void deleteFile(Long id) {
        FileMetadata metadata = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found with id: " + id));

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(metadata.getS3Bucket())
                    .key(metadata.getS3Key())
                    .build());
        } catch (SdkException e) {
            throw new FileStorageException("Failed to delete file from S3: " + metadata.getS3Key(), e);
        }

        fileMetadataRepository.delete(metadata);
    }

    private FileDto toDto(FileMetadata metadata, String presignedUrl) {
        return new FileDto(
                metadata.getId(),
                metadata.getOriginalFilename(),
                metadata.getContentType(),
                metadata.getFileSize(),
                metadata.getUploadedAt(),
                presignedUrl);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
