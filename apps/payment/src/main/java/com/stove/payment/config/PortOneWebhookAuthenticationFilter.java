package com.stove.payment.config;

import com.stove.common.web.error.BusinessException;
import com.stove.payment.core.port.PortOneWebhookVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security boundary: authenticate bounded original bytes before MVC conversion. */
public class PortOneWebhookAuthenticationFilter extends OncePerRequestFilter {
    private final PortOneWebhookVerifier verifier;
    private final String expectedStoreId;

    public PortOneWebhookAuthenticationFilter(PortOneWebhookVerifier verifier, String expectedStoreId) {
        this.verifier = verifier;
        this.expectedStoreId = expectedStoreId;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/v1/payments/webhooks/portone".equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(65537);
        if (body.length > 65536) { response.setStatus(413); return; }
        try {
            verifier.verify(body, header(request, "webhook-id"), header(request, "webhook-timestamp"),
                    header(request, "webhook-signature"));
        } catch (BusinessException rejected) {
            response.setStatus(401);
            return;
        }
        request.setAttribute("payment.portone.expected-store", expectedStoreId);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream bytes = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return bytes.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return bytes.read(target, offset, length); }
                    @Override public boolean isFinished() { return bytes.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException("Synchronous webhook body required");
                    }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
        }, response);
    }

    private static String header(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }
}
