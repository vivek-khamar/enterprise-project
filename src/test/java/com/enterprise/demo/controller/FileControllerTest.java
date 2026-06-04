package com.enterprise.demo.controller;

import com.enterprise.demo.dto.FileDto;
import com.enterprise.demo.exception.FileStorageException;
import com.enterprise.demo.exception.InvalidFileException;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.enterprise.demo.security.JwtUtil;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Test
    void uploadFile_returns201WithFileDto() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "pdf content".getBytes());

        FileDto response = new FileDto(1L, "report.pdf", "application/pdf", 11L, Instant.now(), null);
        when(fileStorageService.uploadFile(any())).thenReturn(response);

        mockMvc.perform(multipart("/api/v1/files").with(user("user").roles("USER")).file(mockFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileSize").value(11));
    }

    @Test
    void uploadFile_returns400WhenFileIsEmpty() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", "content".getBytes());

        when(fileStorageService.uploadFile(any()))
                .thenThrow(new InvalidFileException("Cannot upload empty file"));

        mockMvc.perform(multipart("/api/v1/files").with(user("user").roles("USER")).file(mockFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid file"))
                .andExpect(jsonPath("$.details").value("Cannot upload empty file"));
    }

    @Test
    void uploadFile_returns500OnS3Error() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "content".getBytes());

        when(fileStorageService.uploadFile(any()))
                .thenThrow(new FileStorageException("Failed to upload file to S3: report.pdf"));

        mockMvc.perform(multipart("/api/v1/files").with(user("user").roles("USER")).file(mockFile))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("File storage error"))
                .andExpect(jsonPath("$.details").value("Failed to upload file to S3: report.pdf"));
    }

    @Test
    void listFiles_returns200WithFileList() throws Exception {
        List<FileDto> files = List.of(
                new FileDto(1L, "a.png", "image/png", 200L, Instant.now(), null),
                new FileDto(2L, "b.pdf", "application/pdf", 500L, Instant.now(), null));
        when(fileStorageService.listFiles()).thenReturn(files);

        mockMvc.perform(get("/api/v1/files").with(user("user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].originalFilename").value("a.png"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].originalFilename").value("b.pdf"));
    }

    @Test
    void listFiles_returns200WithEmptyList() throws Exception {
        when(fileStorageService.listFiles()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/files").with(user("user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listFiles_presignedUrlIsAbsentWhenNull() throws Exception {
        List<FileDto> files = List.of(
                new FileDto(1L, "photo.jpg", "image/jpeg", 300L, Instant.now(), null));
        when(fileStorageService.listFiles()).thenReturn(files);

        mockMvc.perform(get("/api/v1/files").with(user("user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].presignedUrl").doesNotExist());
    }

    @Test
    void getFile_returns200WithPresignedUrl() throws Exception {
        FileDto file = new FileDto(1L, "report.pdf", "application/pdf", 1024L, Instant.now(),
                "https://s3.amazonaws.com/bucket/uploads/uuid_report.pdf?X-Amz-Signature=abc");
        when(fileStorageService.getFile(1L)).thenReturn(file);

        mockMvc.perform(get("/api/v1/files/1").with(user("user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.presignedUrl").value(
                        "https://s3.amazonaws.com/bucket/uploads/uuid_report.pdf?X-Amz-Signature=abc"));
    }

    @Test
    void getFile_returns404WhenNotFound() throws Exception {
        when(fileStorageService.getFile(99L))
                .thenThrow(new ResourceNotFoundException("File not found with id: 99"));

        mockMvc.perform(get("/api/v1/files/99").with(user("user").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.details").value("File not found with id: 99"));
    }

    @Test
    void deleteFile_returns204OnSuccess() throws Exception {
        doNothing().when(fileStorageService).deleteFile(1L);

        mockMvc.perform(delete("/api/v1/files/1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteFile_returns404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("File not found with id: 99"))
                .when(fileStorageService).deleteFile(99L);

        mockMvc.perform(delete("/api/v1/files/99").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.details").value("File not found with id: 99"));
    }

    @Test
    void deleteFile_returns500OnS3Error() throws Exception {
        doThrow(new FileStorageException("Failed to delete file from S3: uploads/uuid_file.txt"))
                .when(fileStorageService).deleteFile(eq(1L));

        mockMvc.perform(delete("/api/v1/files/1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("File storage error"));
    }

    @Test
    void uploadFile_acceptsMultipartFormDataContentType() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "image.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});

        FileDto response = new FileDto(3L, "image.png", "image/png", 3L, Instant.now(), null);
        when(fileStorageService.uploadFile(any())).thenReturn(response);

        mockMvc.perform(multipart("/api/v1/files").with(user("user").roles("USER")).file(mockFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }
}
