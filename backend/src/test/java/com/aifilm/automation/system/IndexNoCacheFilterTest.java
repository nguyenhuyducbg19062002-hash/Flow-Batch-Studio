package com.aifilm.automation.system;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IndexNoCacheFilterTest {

    private String cacheControl(String uri) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new IndexNoCacheFilter().doFilter(new MockHttpServletRequest("GET", uri), res, new MockFilterChain());
        return res.getHeader("Cache-Control");
    }

    @Test
    void indexIsNeverCached() throws Exception {
        assertEquals("no-store", cacheControl("/"));
        assertEquals("no-store", cacheControl("/index.html"));
    }

    @Test
    void hashedAssetsAndApiAreLeftAlone() throws Exception {
        assertNull(cacheControl("/assets/index-C_as-GAt.js"));
        assertNull(cacheControl("/api/system/info"));
    }
}
