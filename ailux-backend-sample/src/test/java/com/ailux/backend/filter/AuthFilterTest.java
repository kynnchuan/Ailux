package com.ailux.backend.filter;

import com.ailux.backend.model.User;
import com.ailux.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AuthFilter - Token-based authentication")
class AuthFilterTest {

    private AuthFilter authFilter;
    private UserRepository userRepository;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authFilter = new AuthFilter(userRepository, new ObjectMapper());
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("Non-API requests pass through without auth")
    void nonApiRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/h2-console");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("API request without token returns 401")
    void apiRequestWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("missing_token"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("API request with invalid token returns 401")
    void apiRequestWithInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chat/completions");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userRepository.findByToken("invalid-token")).thenReturn(Optional.empty());

        authFilter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("invalid_token"));
    }

    @Test
    @DisplayName("API request with valid token passes through")
    void apiRequestWithValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chat/completions");
        request.addHeader("Authorization", "Bearer token-pro-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        User user = new User();
        user.setId("pro_user");
        user.setToken("token-pro-001");
        when(userRepository.findByToken("token-pro-001")).thenReturn(Optional.of(user));

        authFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Auth header without Bearer prefix is rejected")
    void authHeaderWithoutBearer() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/chat/completions");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
    }
}
