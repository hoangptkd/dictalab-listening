package com.dictalab.service;

import com.dictalab.config.MediaStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3AudioProxyTest {
    @Test void streamsByteRangesSupportsHeadAndFailsClosedWithoutLocalFallback() throws Exception {
        var s3=mock(S3Client.class);
        var properties=new MediaStorageProperties(Path.of("target/test-media"),DataSize.ofMegabytes(25),
                new MediaStorageProperties.S3(true,"test-bucket","us-east-1","dictalab/audio/",null));
        var service=new MediaStorageService(properties,Optional.of(s3),Optional.of(mock(S3Presigner.class)));
        String name="00000000-0000-0000-0000-000000000001.wav";
        var request=new MockHttpServletRequest("GET","/media/s3/"+name);request.addHeader("Range","bytes=0-3");
        var response=new MockHttpServletResponse();
        var metadataBuilder=GetObjectResponse.builder().contentLength(4L).contentRange("bytes 0-3/48").contentType("audio/wav");
        // Content-Range must produce 206 even when upstream status metadata reports 200.
        metadataBuilder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        var metadata=metadataBuilder.build();
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(metadata,new ByteArrayInputStream(new byte[]{'R','I','F','F'})));
        service.serveManagedAudio(name,request,response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 0-3/48");
        assertThat(response.getContentAsByteArray()).containsExactly((byte)'R',(byte)'I',(byte)'F',(byte)'F');
        verify(s3).getObject(argThat((GetObjectRequest r)->r.range().equals("bytes=0-3") && r.key().equals("dictalab/audio/"+name)));
        var head=new MockHttpServletRequest("HEAD","/media/s3/"+name);
        var headBuilder=HeadObjectResponse.builder().contentLength(48L).contentType("audio/wav");
        headBuilder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(headBuilder.build());
        var headers=new MockHttpServletResponse();service.serveManagedAudio(name,head,headers);
        assertThat(headers.getHeader("Content-Length")).isEqualTo("48");assertThat(headers.getContentAsByteArray()).isEmpty();
        request.removeHeader("Range");request.addHeader("Range","bytes=0-3,8-10");
        var invalid=new MockHttpServletResponse();service.serveManagedAudio(name,request,invalid);assertThat(invalid.getStatus()).isEqualTo(416);
        request.removeHeader("Range");
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        var failed=new MockHttpServletResponse();service.serveManagedAudio(name,request,failed);
        assertThat(failed.getStatus()).isEqualTo(503);assertThat(failed.getContentAsByteArray()).isEmpty();
    }
}
