package com.travel.agent;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

record ImageInput(String url, String base64, String mime) {
  boolean exists() {
    return !url.isEmpty() || !base64.isEmpty();
  }

  String visionUrl() {
    return url.isEmpty() ? "data:" + mime + ";base64," + base64 : url;
  }

  static ImageInput prepare(Inputs inputs, int maxBytes) {
    String url = inputs.imageUrls().isEmpty() ? "" : inputs.imageUrls().getFirst().strip();
    String encoded = inputs.imageBase64().isEmpty() ? "" : inputs.imageBase64().getFirst().strip();
    if (!url.isEmpty() && !encoded.isEmpty()) {
      throw new AgentException("400", "image_url和image_base64不能同时提供");
    }
    if (!url.isEmpty()) {
      return fromUrl(url);
    }
    if (encoded.isEmpty()) {
      return new ImageInput("", "", "");
    }
    return decodeBase64(encoded, maxBytes);
  }

  private static ImageInput fromUrl(String url) {
    URI uri;
    try {
      uri = AgentSettings.httpUri(url);
    } catch (IllegalArgumentException e) {
      throw new AgentException("400", "图片地址必须是有效的HTTP或HTTPS链接");
    }
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    String mime = path.endsWith(".png") ? "image/png" : "image/jpeg";
    if (!path.endsWith(".jpg") && !path.endsWith(".jpeg") && !path.endsWith(".png")) {
      throw new AgentException("400", "仅支持JPG、JPEG、PNG格式图片");
    }
    return new ImageInput(url, "", mime);
  }

  private static ImageInput decodeBase64(String encoded, int maxBytes) {
    encoded = stripDataUriPrefix(encoded);
    encoded = encoded.replaceAll("(?U)\\s", "").replace('-', '+').replace('_', '/');
    if (encoded.length() > 4L * ((maxBytes + 2L) / 3)) {
      throw new AgentException("400", "图片大小超限");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new AgentException("400", "Base64图片数据无效");
    }
    if (decoded.length == 0) {
      throw new AgentException("400", "Base64图片数据无效");
    }
    if (decoded.length > maxBytes) {
      throw new AgentException("400", "图片大小超限");
    }
    String mime = detectMime(decoded);
    return new ImageInput("", Base64.getEncoder().encodeToString(decoded), mime);
  }

  private static String stripDataUriPrefix(String encoded) {
    if (!encoded.startsWith("data:")) {
      return encoded;
    }
    int comma = encoded.indexOf(',');
    if (comma < 0 || !encoded.substring(0, comma).toLowerCase(Locale.ROOT).endsWith(";base64")) {
      throw new AgentException("400", "Base64图片数据无效");
    }
    return encoded.substring(comma + 1);
  }

  private static String detectMime(byte[] decoded) {
    if (startsWith(decoded, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
      return "image/png";
    }
    if (startsWith(decoded, new byte[] {(byte) 255, (byte) 216, (byte) 255})) {
      return "image/jpeg";
    }
    throw new AgentException("400", "仅支持JPG、JPEG、PNG格式图片");
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return data.length >= prefix.length
        && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
  }
}
