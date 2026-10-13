package dev.darshan.spotme.media;

import dev.darshan.spotme.config.S3Properties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Photos in an S3-compatible bucket (MinIO locally, any S3 service in production). The bucket stays
 * private: guests get photos through the app's own signed links, which check access and then hand out
 * a bucket link signed for a short time (spotme.s3.link-ttl).
 */
@Component
@ConditionalOnProperty(name = "spotme.storage-type", havingValue = "s3")
public class S3PhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(S3PhotoStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties props;

    public S3PhotoStorage(S3Properties props) {
        this.props = props;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        // MinIO serves buckets as http://host/bucket/key, not as a sub-domain
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        createBucketIfMissing();
    }

    private void createBucketIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(props.bucket()));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(props.bucket()));
            log.info("Created bucket {}", props.bucket());
        }
    }

    @Override
    public void put(String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(props.bucket()).key(key).contentType("image/jpeg"), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) throws IOException {
        try {
            return s3.getObjectAsBytes(request(key)).asByteArray();
        } catch (NoSuchKeyException e) {
            // the same exception the local storage throws, so callers handle a missing photo one way
            throw new NoSuchFileException(key);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(request(key));
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(key);
        }
    }

    @Override
    public void delete(String key) {
        // S3 answers a delete of a missing key with success, which is what the purge retries need
        s3.deleteObject(b -> b.bucket(props.bucket()).key(key));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(props.bucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public List<StoredObject> list(String prefix) {
        // "events/abc" must not also match "events/abcd/..."
        String folder = prefix.endsWith("/") ? prefix : prefix + "/";
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(props.bucket()).prefix(folder).build();
        List<StoredObject> out = new ArrayList<>();
        for (S3Object object : s3.listObjectsV2Paginator(request).contents()) {
            out.add(new StoredObject(object.key(), object.lastModified()));
        }
        return out;
    }

    @Override
    public Optional<URI> directLink(String key, String downloadName) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(props.bucket()).key(key);
        if (downloadName != null) {
            get.responseContentDisposition("attachment; filename=\"" + downloadName + "\"");
        }
        var signed = presigner.presignGetObject(p -> p.signatureDuration(props.linkTtl()).getObjectRequest(get.build()));
        return Optional.of(URI.create(signed.url().toString()));
    }

    @PreDestroy
    public void close() {
        presigner.close();
        s3.close();
    }

    private GetObjectRequest request(String key) {
        return GetObjectRequest.builder().bucket(props.bucket()).key(key).build();
    }
}
