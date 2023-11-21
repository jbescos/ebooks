package es.tododev.ebooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class Main {

    private static final String BASE_URL_KEY = "base.url";
    private static final String ISBNS_KEY = "isbns";
    private static final String DRM_KEY = "DRM";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Specify the properties file as argument");
        } else {
            Properties properties = new Properties();
            File prop = new File(args[0]);
            if (!prop.exists()) {
                throw new IllegalArgumentException("File " + args[0] + " does not exist");
            }
            try (InputStream input = new FileInputStream(args[0])) {
                properties.load(input);
            }
            Map<String, String> headers = new HashMap<>();
            for (Object keyObj : properties.keySet()) {
                String key = (String) keyObj;
                if (key.startsWith("header.")) {
                    headers.put(key.replaceFirst("header.", ""), properties.getProperty(key).toString());
                }
            }
            String baseUrl = properties.getProperty(BASE_URL_KEY).toString();
            Boolean drm = Boolean.parseBoolean(properties.getProperty(DRM_KEY));
            for (String isbn : properties.getProperty(ISBNS_KEY).toString().split(",")) {
                System.out.println("Processing ISBN: " + isbn);
                Processor processor;
                if (drm) {
                    processor = new HtmlProcessor(baseUrl, isbn, headers);
                } else {
                    processor = new EpubProcessor(baseUrl, isbn, headers);
                }
                processor.execute();
            }
        }
    }

}
