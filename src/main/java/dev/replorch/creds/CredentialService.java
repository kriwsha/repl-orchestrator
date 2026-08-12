package dev.replorch.creds;

import dev.replorch.dto.CredentialRequest;
import dev.replorch.spi.Credential;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts write-only credential API payloads into resolved runtime credentials. */
@Service
public class CredentialService {

    private static final String PREFIX = "jdbc:postgresql://";
    private static final int DEFAULT_PORT = 5432;
    private static final String DEFAULT_SSLMODE = "require";

    public Credential fromRequest(CredentialRequest req) {
        ParsedJdbcUrl parsed = parse(req.jdbcUrl());
        return new Credential(
                parsed.jdbcUrl(),
                parsed.hosts(),
                parsed.database(),
                req.username(),
                req.password(),
                parsed.sslmode(),
                parsed.targetServerType());
    }

    private ParsedJdbcUrl parse(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        if (!jdbcUrl.startsWith(PREFIX)) {
            throw new IllegalArgumentException("jdbcUrl must start with " + PREFIX);
        }

        String withoutPrefix = jdbcUrl.substring(PREFIX.length());
        int pathStart = withoutPrefix.indexOf('/');
        if (pathStart < 0) {
            throw new IllegalArgumentException("jdbcUrl must include database path");
        }

        String hostsPart = withoutPrefix.substring(0, pathStart);
        if (hostsPart.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must include at least one host");
        }

        String pathAndQuery = withoutPrefix.substring(pathStart + 1);
        int queryStart = pathAndQuery.indexOf('?');
        String database = queryStart < 0 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
        if (database.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must include database name");
        }

        String query = queryStart < 0 ? "" : pathAndQuery.substring(queryStart + 1);
        Map<String, String> params = queryParams(query);
        String sslmode = valueOrDefault(params.get("sslmode"), DEFAULT_SSLMODE);
        String targetServerType = params.get("targetservertype");

        return new ParsedJdbcUrl(
                jdbcUrl,
                parseHosts(hostsPart),
                decode(database),
                sslmode,
                targetServerType);
    }

    private List<Credential.HostPort> parseHosts(String hostsPart) {
        List<String> entries = splitHosts(hostsPart);
        List<Credential.HostPort> hosts = new ArrayList<>(entries.size());
        for (String entry : entries) {
            hosts.add(parseHost(entry));
        }
        return List.copyOf(hosts);
    }

    private List<String> splitHosts(String hostsPart) {
        List<String> hosts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inIpv6 = false;

        for (int i = 0; i < hostsPart.length(); i++) {
            char ch = hostsPart.charAt(i);
            if (ch == '[') {
                inIpv6 = true;
            } else if (ch == ']') {
                inIpv6 = false;
            }

            if (ch == ',' && !inIpv6) {
                addHostEntry(hosts, current);
            } else {
                current.append(ch);
            }
        }
        addHostEntry(hosts, current);
        return hosts;
    }

    private void addHostEntry(List<String> hosts, StringBuilder current) {
        String entry = current.toString().trim();
        if (entry.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl contains an empty host entry");
        }
        hosts.add(entry);
        current.setLength(0);
    }

    private Credential.HostPort parseHost(String entry) {
        String host;
        String portText = null;
        if (entry.startsWith("[")) {
            int close = entry.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("invalid IPv6 host entry: " + entry);
            }
            host = entry.substring(1, close);
            if (entry.length() > close + 1) {
                if (entry.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("invalid host entry: " + entry);
                }
                portText = entry.substring(close + 2);
            }
        } else {
            int colon = entry.lastIndexOf(':');
            if (colon >= 0) {
                host = entry.substring(0, colon);
                portText = entry.substring(colon + 1);
            } else {
                host = entry;
            }
        }

        if (host.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl contains a host entry without host name");
        }
        return new Credential.HostPort(decode(host), parsePort(portText));
    }

    private int parsePort(String portText) {
        if (portText == null || portText.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535: " + portText);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + portText, e);
        }
    }

    private Map<String, String> queryParams(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key).toLowerCase(Locale.ROOT), decode(value));
        }
        return params;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record ParsedJdbcUrl(
            String jdbcUrl,
            List<Credential.HostPort> hosts,
            String database,
            String sslmode,
            String targetServerType) {}
}
