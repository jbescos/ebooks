package es.tododev.ebooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

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
            List<String> failed = Collections.synchronizedList(new ArrayList<String>());
            Stream.of(properties.getProperty(ISBNS_KEY).toString().split(",")).parallel().forEach(isbn -> {
                System.out.println("Processing ISBN: " + isbn);
                Processor processor;
                if (drm) {
                    processor = new AntiDRMProcessor(baseUrl, isbn, headers);
                } else {
                    processor = new EpubProcessor(baseUrl, isbn, headers);
                }
                try {
                    processor.execute();
                } catch (Exception e) {
                    e.printStackTrace();
                    failed.add(isbn);
                }
            });
            if (!failed.isEmpty()) {
                System.out.println("Errors in: " + failed);
            }
        }
    }

}
