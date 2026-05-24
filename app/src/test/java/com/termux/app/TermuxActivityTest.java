package com.termux.app;

import com.termux.shared.termux.data.TermuxUrlUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;

public class TermuxActivityTest {

    private void assertUrlsAre(String text, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, TermuxUrlUtils.extractUrls(text));
    }

    @Test
    public void testExtractUrls() {
        assertUrlsAre("hello http://example.com world", "http://example.com");

        assertUrlsAre("http://example.com\nhttp://another.com", "http://example.com", "http://another.com");

        assertUrlsAre("hello http://example.com world and http://more.example.com with secure https://more.example.com",
            "http://example.com", "http://more.example.com", "https://more.example.com");

        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar",
            "https://example.com/#bar", "https://example.com/foo#bar");

        assertUrlsAre("login at https://auth.openai.com/authorize?client_id=codex&code_challenge=a.b-c_d~e%2Ff&state=xyz#done",
            "https://auth.openai.com/authorize?client_id=codex&code_challenge=a.b-c_d~e%2Ff&state=xyz#done");

        assertUrlsAre("open (https://example.com/callback?code=abc&state=xyz).",
            "https://example.com/callback?code=abc&state=xyz");

        assertUrlsAre("balanced https://example.com/path(foo)",
            "https://example.com/path(foo)");
    }

}
