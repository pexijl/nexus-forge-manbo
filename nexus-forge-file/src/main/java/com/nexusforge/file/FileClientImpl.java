package com.nexusforge.file;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;

@Service
public class FileClientImpl implements FileClient{
    @Override
    public FileMeta upload(FileBizType biz, Long ownerId, String filename, String contentType, long size, InputStream in) {
        return null;
    }

    @Override
    public UploadCredential issueUploadCredential(FileBizType biz, Long ownerId, String filename, String contentType, Duration ttl) {
        return null;
    }

    @Override
    public String issueReadUrl(String key, Duration ttl) {
        return "";
    }

    @Override
    public void delete(String key) {

    }

    @Override
    public void deleteByUrl(String url) {

    }
}
