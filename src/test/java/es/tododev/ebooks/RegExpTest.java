package es.tododev.ebooks;

import java.util.regex.Matcher;

import org.junit.Test;

public class RegExpTest {

    @Test
    public void test() {
        String img = "<img alt=\"sdsd\" src=\"test1\">sdfdsfdf<img alt=\"sdsd\" src=\"test2\">";
        Matcher matcher = NoDRMProcessor.PATTERN.matcher(img);
        while (matcher.find()) {
            System.out.println(matcher.group(1));
        }
    }
}
