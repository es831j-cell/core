package com.distressedelk.lumi.intelligence;
import java.util.*;
import java.util.regex.Pattern;
/** Code357 rules-first functional router. It chooses capability, not prose. */
public final class ReasoningRouter {
  private static final Pattern SIMPLE_MATH = Pattern.compile("^\\s*[0-9().+\\-*/% ]+\\s*$");
  private static final List<String> FRESH_LOOKUP = List.of(
      "current","currently","right now","now","weather","temperature","stock price","share price","price of","score","standings","schedule","open now","president","ceo","version");
  private static final List<String> FRESH_RESEARCH = List.of(
      "latest","today","tonight","tomorrow","yesterday","recent","news","this week","what happened","breaking",
      "what's new","whats new","what is new","new with","going on with","updates on","update on");
  private static final List<String> RESEARCH = List.of(
      "compare","evidence","sources","research","pros and cons","what caused","conflicting","verify","best option","analyze","evaluate","recommend","which is better","risks");
  private static final List<String> COMPLEX = List.of(
      "design","debug","reason through","strategy","architecture","optimize","write code","diagnose","plan this","figure out","troubleshoot");

  public RouteDecision decide(String question, boolean online, boolean externalAiHealthy) {
    String raw = question == null ? "" : question.trim();
    String q = raw.toLowerCase(Locale.ROOT);
    if (q.isEmpty()) return new RouteDecision(RouteDecision.Route.DIRECT,false,0,"empty input");
    if (SIMPLE_MATH.matcher(q).matches()) return new RouteDecision(RouteDecision.Route.DIRECT,false,0,"deterministic arithmetic");

    boolean freshResearch=containsAny(q,FRESH_RESEARCH);
    boolean freshLookup=containsAny(q,FRESH_LOOKUP);
    boolean research=containsAny(q,RESEARCH) || raw.length()>420;
    boolean complex=containsAny(q,COMPLEX) || raw.length()>700;

    if (online && (freshResearch || (research && freshLookup)))
      return new RouteDecision(RouteDecision.Route.WEB_RESEARCH,true,3,"fresh question benefits from independent source comparison");
    if (online && research)
      return new RouteDecision(RouteDecision.Route.WEB_RESEARCH,false,3,"question benefits from multi-source evidence");
    if (online && freshLookup)
      return new RouteDecision(RouteDecision.Route.WEB_LOOKUP,true,2,"fresh fact requested");
    if (complex && externalAiHealthy)
      return new RouteDecision(RouteDecision.Route.EXTERNAL_AI,false,0,"complex reasoning benefits from configured stronger AI");
    boolean fresh=freshResearch||freshLookup;
    return new RouteDecision(RouteDecision.Route.LOCAL_MODEL,fresh,0,fresh?"fresh data requested but network unavailable":"local reasoning is sufficient");
  }
  private static boolean containsAny(String q,List<String> xs){ for(String x:xs) if(q.contains(x)) return true; return false; }
}
