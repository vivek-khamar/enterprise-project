package com.enterprise.demo.service;

import com.enterprise.demo.config.S3Properties;
import com.enterprise.demo.dto.FileDto;
import com.enterprise.demo.entity.FileMetadata;
import com.enterprise.demo.exception.FileStorageException;
import com.enterprise.demo.exception.InvalidFileException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private S3Properties s3Properties;

    @InjectMocks
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        lenient().when(s3Properties.getBucketName()).thenReturn("test-bucket");
        lenient().when(s3Properties.getPresignedUrlExpiryMinutes()).thenReturn(15L);
    }

    @Test
    void uploadFile_savesMetadataAndReturnsDto() throws Exception {
        MultipartFile file = mockMultipartFile("test.txt", "text/plain", "content".getBytes());

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        FileMetadata saved = buildMetadata(1L, "test.txt", "uploads/uuid_test.txt", "text/plain", 7L);
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenReturn(saved);

        FileDto result = fileStorageService.uploadFile(file);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getOriginalFilename()).isEqualTo("test.txt");
        assertThat(result.getContentType()).isEqualTo("text/plain");
        assertThat(result.getFileSize()).isEqualTo(7L);
        assertThat(result.getPresignedUrl()).isNull();
    }

    @Test
    void uploadFile_savesCorrectMetadataToRepository() throws Exception {
        MultipartFile file = mockMultipartFile("my file.pdf", "application/pdf", new byte[512]);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        FileMetadata saved = buildMetadata(2L, "my file.pdf", "uploads/uuid_my_file.pdf", "application/pdf", 512L);
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenReturn(saved);

        fileStorageService.uploadFile(file);

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileMetadataRepository).save(captor.capture());
        FileMetadata captured = captor.getValue();

        assertThat(captured.getOriginalFilename()).isEqualTo("my file.pdf");
        assertThat(captured.getS3Bucket()).isEqualTo("test-bucket");
        assertThat(captured.getS3Key()).startsWith("uploads/");
        assertThat(captured.getContentType()).isEqualTo("application/pdf");
        assertThat(captured.getFileSize()).isEqualTo(512L);
        assertThat(captured.getUploadedAt()).isNotNull();
    }

    @Test
    void uploadFile_sanitizesFilenameInS3Key() throws Exception {
        MultipartFile file = mockMultipartFile("my file (1).pdf", "application/pdf", new byte[10]);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        FileMetadata saved = buildMetadata(1L, "my file (1).pdf", "uploads/uuid_my_file__1_.pdf", "application/pdf", 10L);
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenReturn(saved);

        fileStorageService.uploadFile(file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).matches("uploads/[\\w-]+_my_file__1_.pdf");
    }

    @Test
    void uploadFile_usesOctetStreamWhenContentTypeIsNull() throws Exception {
        MultipartFile file = mockMultipartFile("data.bin", null, new byte[20]);

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        FileMetadata saved = buildMetadata(1L, "data.bin", "uploads/uuid_data.bin", "application/octet-stream", 20L);
        when(fileMetadataRepository.save(any(FileMetadata.class))).thenReturn(saved);

        fileStorageService.uploadFile(file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void uploadFile_throwsInvalidFileExceptionForEmptyFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> fileStorageService.uploadFile(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("empty");

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(fileMetadataRepository, never()).save(any());
    }

    @Test
    void uploadFile_throwsFileStorageExceptionOnSdkError() throws Exception {
        MultipartFile file = mockMultipartFile("test.txt", "text/plain", "content".getBytes());

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Connection refused"));

        assertThatThrownBy(() -> fileStorageService.uploadFile(file))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Failed to upload file to S3");

        verify(fileMetadataRepository, never()).save(any());
    }

    @Test
    void uploadFile_throwsFileStorageExceptionOnIoError() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("test.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getSize()).thenReturn(100L);
        when(file.getBytes()).thenThrow(new IOException("disk read error"));

        assertThatThrownBy(() -> fileStorageService.uploadFile(file))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Failed to read file content");

        verify(fileMetadataRepository, never()).save(any());
    }

    @Test
    void getFile_returnsFileDtoWithPresignedUrl() throws Exception {
        FileMetadata metadata = buildMetadata(1L, "report.pdf", "uploads/uuid_report.pdf", "application/pdf", 1024L);
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));

        PresignedGetObjectRequest presignedReq = mock(PresignedGetObjectRequest.class);
        when(presignedReq.url()).thenReturn(URI.create("https://s3.amazonaws.com/test-bucket/uploads/uuid_report.pdf").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedReq);

        FileDto result = fileStorageService.getFile(1L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(result.getPresignedUrl()).isEqualTo("https://s3.amazonaws.com/test-bucket/uploads/uuid_report.pdf");
    }

    @Test
    void getFile_throwsResourceNotFoundExceptionWhenNotFound() {
        when(fileMetadataRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileStorageService.getFile(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void listFiles_returnsAllFilesWithoutPresignedUrls() {
        List<FileMetadata> stored = List.of(
                buildMetadata(1L, "a.png", "uploads/uuid_a.png", "image/png", 200L),
                buildMetadata(2L, "b.pdf", "uploads/uuid_b.pdf", "application/pdf", 500L));
        when(fileMetadataRepository.findAll()).thenReturn(stored);

        List<FileDto> result = fileStorageService.listFiles();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getOriginalFilename()).isEqualTo("a.png");
        assertThat(result.get(0).getPresignedUrl()).isNull();
        assertThat(result.get(1).getId()).isEqualTo(2L);
    }

    @Test
    void listFiles_returnsEmptyListWhenNoFiles() {
        when(fileMetadataRepository.findAll()).thenReturn(List.of());

        assertThat(fileStorageService.listFiles()).isEmpty();
    }

    @Test
    void deleteFile_deletesFromS3ThenDatabase() {
        FileMetadata metadata = buildMetadata(1L, "to-delete.txt", "uploads/uuid_to-delete.txt", "text/plain", 50L);
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        fileStorageService.deleteFile(1L);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("uploads/uuid_to-delete.txt");

        verify(fileMetadataRepository).delete(metadata);
    }

    @Test
    void deleteFile_throwsResourceNotFoundExceptionWhenNotFound() {
        when(fileMetadataRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileStorageService.deleteFile(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteFile_throwsFileStorageExceptionOnSdkError() {
        FileMetadata metadata = buildMetadata(1L, "file.txt", "uploads/uuid_file.txt", "text/plain", 10L);
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.create("S3 unreachable"));

        assertThatThrownBy(() -> fileStorageService.deleteFile(1L))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Failed to delete file from S3");

        verify(fileMetadataRepository, never()).delete(any());
    }

    private MultipartFile mockMultipartFile(String filename, String contentType, byte[] content) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getBytes()).thenReturn(content);
        return file;
    }

    private FileMetadata buildMetadata(Long id, String filename, String s3Key, String contentType, Long size) {
        FileMetadata m = new FileMetadata();
        m.setId(id);
        m.setOriginalFilename(filename);
        m.setS3Key(s3Key);
        m.setS3Bucket("test-bucket");
        m.setContentType(contentType);
        m.setFileSize(size);
        m.setUploadedAt(Instant.now());
        return m;
    }
}
