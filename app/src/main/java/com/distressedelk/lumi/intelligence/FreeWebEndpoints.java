package com.distressedelk.lumi.intelligence;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
public final class FreeWebEndpoints {
  private static String q(String s){ return URLEncoder.encode(s, StandardCharsets.UTF_8); }
  public static String duckDuckGoInstant(String query){ return "https://api.duckduckgo.com/?q="+q(query)+"&format=json&no_html=1&no_redirect=1&skip_disambig=1"; }
  public static String wikipediaOpenSearch(String query){ return "https://en.wikipedia.org/w/api.php?action=opensearch&limit=5&namespace=0&format=json&search="+q(query); }
  public static String wikipediaSummary(String title){ return "https://en.wikipedia.org/api/rest_v1/page/summary/"+q(title).replace("+","%20"); }
  public static String googleNewsRss(String query){ return "https://news.google.com/rss/search?q="+q(query)+"&hl=en-US&gl=US&ceid=US:en"; }
  public static String openMeteoGeocode(String place){ return "https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name="+q(place); }
  public static String openMeteoForecast(double lat,double lon){ return "https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&temperature_unit=fahrenheit&wind_speed_unit=mph"; }
  private FreeWebEndpoints(){}
}
