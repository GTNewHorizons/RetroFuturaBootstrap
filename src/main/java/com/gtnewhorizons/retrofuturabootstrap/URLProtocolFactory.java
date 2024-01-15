package com.gtnewhorizons.retrofuturabootstrap;

import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * Helper to redirect java.protocol.handler.pkgs classes to use RFB's compat ClassLoader instead of the system ClassLoader.
 */
public class URLProtocolFactory implements URLStreamHandlerFactory {
    public static void register() {
        URL.setURLStreamHandlerFactory(new URLProtocolFactory());
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        String packagePrefixList = System.getProperty("java.protocol.handler.pkgs");
        if (packagePrefixList == null) {
            return null;
        }

        String[] packagePrefixes = packagePrefixList.split("\\|");
        URLStreamHandler handler = null;
        for (int i = 0; handler == null && i < packagePrefixes.length; i++) {
            String packagePrefix = packagePrefixes[i].trim();
            try {
                String clsName = packagePrefix + "." + protocol + ".Handler";
                Class<?> cls = null;
                cls = Class.forName(clsName, true, Main.compatLoader);
                if (!URLStreamHandler.class.isAssignableFrom(cls)) {
                    Main.logger.warn("Class {} is not assignable to a URLStreamHandler", cls.getName());
                    continue;
                }
                Object tmp = cls.getConstructor().newInstance();
                handler = (URLStreamHandler) tmp;
            } catch (Exception e) {
                // when failing to construct anything, silently skip
            }
        }
        return handler;
    }
}
