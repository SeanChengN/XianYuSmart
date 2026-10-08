package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.ProductImageDownloadService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/images")
public class ProductImageDownloadController {
    private final ProductImageDownloadService imageService;

    public ProductImageDownloadController(ProductImageDownloadService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam String url) {
        try {
            var image = imageService.download(url);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .contentLength(image.bytes().length)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("reference." + image.extension()).build().toString())
                    .body(image.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ResultObject.failed(400, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(ResultObject.failed(502, e.getMessage()));
        }
    }
}
