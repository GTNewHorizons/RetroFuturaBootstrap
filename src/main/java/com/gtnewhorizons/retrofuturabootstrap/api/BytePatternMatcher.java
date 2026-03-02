package com.gtnewhorizons.retrofuturabootstrap.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class BytePatternMatcher {
    final Mode mode;
    // first byte -> matched patterns
    final byte[][][] byFirst = new byte[256][][];
    int minPatternLen = Integer.MAX_VALUE;

    public enum Mode {
        Equals,
        StartsWith,
        Contains
    }

    public BytePatternMatcher(String strPattern, Mode mode) {
        this(new String[] {strPattern}, mode);
    }

    public BytePatternMatcher(String[] strPatterns, Mode mode) {
        this.mode = mode;

        final byte[][] patterns = new byte[strPatterns.length][];
        for (int i = 0; i < strPatterns.length; i++) {
            patterns[i] = strPatterns[i].getBytes(StandardCharsets.UTF_8);
        }

        @SuppressWarnings("unchecked")
        final ArrayList<byte[]>[] patternsByFirstByte = new ArrayList[256];

        for (final byte[] pattern : patterns) {
            if (pattern.length < minPatternLen) minPatternLen = pattern.length;

            final int firstByte = pattern[0] & 0xFF;
            ArrayList<byte[]> patternsBucket = patternsByFirstByte[firstByte];
            if (patternsBucket == null) {
                patternsBucket = new ArrayList<>();
                patternsByFirstByte[firstByte] = patternsBucket;
            }
            patternsBucket.add(pattern);
        }

        for (int firstByte = 0; firstByte < 256; firstByte++) {
            final ArrayList<byte[]> patternsBucket = patternsByFirstByte[firstByte];
            if (patternsBucket != null) {
                byFirst[firstByte] = patternsBucket.toArray(new byte[0][0]);
            }
        }
    }

    public boolean matches(byte[] bytes, int start, int len) {
        if (len < minPatternLen) {
            return false;
        }

        if (mode == Mode.Equals) {
            return matchesEquals(bytes, start, len);
        }
        // coming soon: mode == StartsWith (useful for LwjglRedirectTransformer)

        final int end = start + len;

        for (int pos = start; pos <= end - minPatternLen; pos++) {
            final byte[][] patterns = byFirst[bytes[pos] & 0xFF];
            if (patterns == null) {
                continue;
            }

            for (final byte[] pattern : patterns) {
                if (pattern.length > end - pos) {
                    continue;
                }

                int k = pattern.length - 1;
                while (k > 0 && bytes[pos + k] == pattern[k]) k--;
                if (k == 0) return true;
            }
        }

        return false;
    }

    private boolean matchesEquals(byte[] bytes, int start, int len) {
        final byte[][] patterns = byFirst[bytes[start] & 0xFF];
        if (patterns == null) {
            return false;
        }

        for (final byte[] pattern : patterns) {
            if (pattern.length != len) {
                continue;
            }

            int k = pattern.length - 1;
            while (k > 0 && bytes[start + k] == pattern[k]) k--;
            if (k == 0) return true;
        }

        return false;
    }
}
