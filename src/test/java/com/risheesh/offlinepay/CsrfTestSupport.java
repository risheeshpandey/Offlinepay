package com.risheesh.offlinepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Use real cookie/header exchange, so tests never swap the shared CsrfTokenRepository. */
final class CsrfTestSupport {
    static MockHttpServletRequestBuilder authPost(MockMvc mvc, String path) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        var cookie = response.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("XSRF-TOKEN="))
                .map(MockCookie::parse).findFirst().orElseThrow();
        String token = new ObjectMapper().readTree(response.getContentAsString()).get("token").asText();
        return post(path).cookie(cookie).header("X-XSRF-TOKEN", token);
    }
}
