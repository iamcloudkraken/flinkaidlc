package com.flinkaidlc.platform.pipeline;

import com.flinkaidlc.platform.exception.SchemaRegistryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

@Service
public class SchemaRegistryValidationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryValidationService.class);

    /**
     * Private/internal host patterns that must not be contacted (SSRF blocklist).
     */
    private static final List<String> BLOCKED_HOST_PATTERNS = List.of(
            "localhost",
            "127.",
            "10.",
            "169.254.",
            "metadata.internal",
            "169.254.169.254"
    );

    // 172.16.0.0/12 covers 172.16.x.x through 172.31.x.x
    private static final int PRIVATE_172_START = 16;
    private static final int PRIVATE_172_END = 31;

    private final RestClient restClient;

    public SchemaRegistryValidationService() {
        this.restClient = RestClient.builder()
                .build();
    }

    // Package-private constructor for testing
    SchemaRegistryValidationService(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Validates that a schema registry URL is reachable and contains the given Avro subject.
     *
     * @param schemaRegistryUrl the schema registry base URL
     * @param avroSubject       the Avro subject to look up
     * @throws SchemaRegistryException if the URL is blocked, subject not found, or registry unreachable
     */
    public void validate(String schemaRegistryUrl, String avroSubject) {
        checkForSsrf(schemaRegistryUrl);

        String url = schemaRegistryUrl.replaceAll("/+$", "")
                + "/subjects/" + avroSubject + "/versions/latest";

        try {
            restClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SchemaRegistryException(
                        "Avro subject '" + avroSubject + "' not found in registry at " + schemaRegistryUrl);
            }
            throw new SchemaRegistryException(
                    "Schema Registry at " + schemaRegistryUrl + " returned error: " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            throw new SchemaRegistryException(
                    "Schema Registry at " + schemaRegistryUrl + " is not reachable", e);
        } catch (Exception e) {
            throw new SchemaRegistryException(
                    "Schema Registry at " + schemaRegistryUrl + " is not reachable", e);
        }
    }

    private void checkForSsrf(String schemaRegistryUrl) {
        if (schemaRegistryUrl == null || schemaRegistryUrl.isBlank()) {
            throw new SchemaRegistryException("Schema registry URL must not be blank");
        }

        // Only allow http:// or https:// schemes
        if (!schemaRegistryUrl.startsWith("http://") && !schemaRegistryUrl.startsWith("https://")) {
            throw new SchemaRegistryException(
                    "Schema registry URL must use http or https scheme: " + schemaRegistryUrl);
        }

        URI uri;
        try {
            uri = new URI(schemaRegistryUrl);
        } catch (URISyntaxException e) {
            throw new SchemaRegistryException("Invalid schema registry URL: " + schemaRegistryUrl);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SchemaRegistryException("Schema registry URL has no host: " + schemaRegistryUrl);
        }

        String hostLower = host.toLowerCase();

        // Check simple blocked patterns
        for (String blocked : BLOCKED_HOST_PATTERNS) {
            if (hostLower.equals(blocked) || hostLower.startsWith(blocked)) {
                throw new SchemaRegistryException(
                        "Schema registry URL points to a blocked/private address: " + schemaRegistryUrl);
            }
        }

        // Check 192.168.x.x
        if (hostLower.startsWith("192.168.")) {
            throw new SchemaRegistryException(
                    "Schema registry URL points to a blocked/private address: " + schemaRegistryUrl);
        }

        // Check 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
        if (hostLower.startsWith("172.")) {
            String[] parts = hostLower.split("\\.");
            if (parts.length >= 2) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    if (secondOctet >= PRIVATE_172_START && secondOctet <= PRIVATE_172_END) {
                        throw new SchemaRegistryException(
                                "Schema registry URL points to a blocked/private address: " + schemaRegistryUrl);
                    }
                } catch (NumberFormatException ignored) {
                    // Not a numeric IP, fall through
                }
            }
        }
    }
}
