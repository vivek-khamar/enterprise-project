package com.enterprise.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
@Getter
@Setter
public class S3Properties {

    private String region = "us-east-1";
    private String bucketName;
    private String accessKeyId;
    private String secretAccessKey;
    private long presignedUrlExpiryMinutes = 15;
    private String endpointUrl;
}
