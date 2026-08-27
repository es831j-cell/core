package com.distressedelk.lumi;

import android.content.SharedPreferences;
import com.distressedelk.lumi.intelligence.Evidence;
import com.distressedelk.lumi.intelligence.EvidenceSet;
import com.distressedelk.lumi.intelligence.FreeWebEndpoints;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Code357 Functional Core web path.
 * Concise lookups try direct structured endpoints first. Research opens independent pages,
 * scores evidence, records confidence/corroboration, then uses the cash-safe AI ladder only
 * to synthesize the already-retrieved evidence. No configured model is required to return a
 * bounded factual fallback.
 */
final class WebResearchAgent {
    interface Callback{void onSuccess(String answer,String evidence);void onFailure(String error);}
    private WebResearchAgent(){}

    static boolean shouldResearch(String q){
        String l=q==null?"":q.toLowerCase(Locale.US);
        return l.matches(".*\\b(today|current|currently|latest|recent|news|price|schedule|release|version|who is|where is|what happened|this week|right now)\\b.*")
                || l.length()>180
                || l.matches(".*\\b(compare|research|verify|look up|find out|check online|search the web|analyze|evaluate|pros and cons|which is better|what are the risks|recommend)\\b.*");
    }

    static void lookup(SharedPreferences prefs,String query,Callback cb){
        new Thread(()->{
            long started=System.currentTimeMillis();
            try{
                String direct=duckDuckGoLookup(query);
                if(direct!=null && !direct.isBlank()){
                    recordLookup(prefs,query,1,System.currentTimeMillis()-started,0.76f,false,"duckduckgo-instant");
                    cb.onSuccess(direct,"DuckDuckGo Instant Answer");
                    return;
                }
                Lookup wiki=wikipediaLookup(query);
                if(wiki!=null && !wiki.text.isBlank()){
                    recordLookup(prefs,query,1,System.currentTimeMillis()-started,0.84f,false,"wikipedia-summary");
                    cb.onSuccess(compactSentences(wiki.text,3,700)+(wiki.url.isBlank()?"":"\nSource: "+wiki.url),wiki.text);
                    return;
                }
                // Structured lookup did not resolve it. Let the multi-source path do the work.
                cb.onFailure("No structured direct answer");
            }catch(Throwable t){ cb.onFailure(t.getClass().getSimpleName()+": "+safe(t.getMessage())); }
        },"LumiWebLookup").start();
    }

    static void research(SharedPreferences prefs,String query,Callback cb){ research(prefs,query,3,cb); }

    static void research(SharedPreferences prefs,String query,int desiredSources,Callback cb){
        new Thread(()->{
            long started=System.currentTimeMillis();
            try{
                int target=Math.max(2,Math.min(4,desiredSources));
                String qLower=query==null?"":query.toLowerCase(Locale.US);
                boolean newsLike=qLower.matches(".*\\b(news|latest|today|this week|current developments|what happened)\\b.*");
                String searchUrl="https://html.duckduckgo.com/html/?q="+URLEncoder.encode(query,"UTF-8")+(newsLike?"&df=w":"");
                String html=get(searchUrl,9000,13000,350000,"text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
                ArrayList<ResultLink> results=parseResults(html);
                if(results.isEmpty())throw new IOException("No useful search results returned");

                EvidenceSet evidenceSet=new EvidenceSet();
                StringBuilder evidenceText=new StringBuilder();
                int pagesRead=0;
                for(ResultLink r:results){
                    if(pagesRead>=target)break;
                    String targetUrl=unwrapDuckDuckGo(r.href);
                    if(!(targetUrl.startsWith("https://")||targetUrl.startsWith("http://")))continue;
                    try{
                        String page=get(targetUrl,8000,12000,300000,"text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
                        String text=stripPage(page);
                        if(text.length()<120)continue;
                        text=compactSentences(text,8,2600);
                        String publisher=publisher(targetUrl);
                        Evidence ev=new Evidence(r.title,targetUrl,publisher,text,System.currentTimeMillis(),quality(targetUrl),true);
                        evidenceSet.add(ev);
                        pagesRead++;
                    }catch(Throwable ignored){}
                }
                if(evidenceSet.size()==0) throw new IOException("Search returned links but no readable pages");

                int n=0;
                for(Evidence ev:evidenceSet.ranked()){
                    n++;
                    evidenceText.append("SOURCE ").append(n).append(": ").append(ev.title()).append("\nURL: ").append(ev.url()).append("\n").append(ev.text()).append("\n\n");
                }
                final String evText=evidenceText.toString();
                final int sourceCount=evidenceSet.size();
                final float confidence=(float)evidenceSet.confidence();
                final boolean corroborated=evidenceSet.hasIndependentCorroboration();
                final long retrievalMs=System.currentTimeMillis()-started;
                prefs.edit().putString("last_web_query",query)
                        .putInt("last_web_source_count",sourceCount)
                        .putInt("last_web_pages_read",pagesRead)
                        .putFloat("last_web_evidence_confidence",confidence)
                        .putBoolean("last_web_independent_corroboration",corroborated)
                        .putLong("last_web_retrieval_ms",retrievalMs)
                        .putLong("last_web_research_at",System.currentTimeMillis()).apply();

                if(!CloudBrainRouter.anyConfigured(prefs)){
                    cb.onSuccess(deterministicEvidenceAnswer(evidenceSet),evText);
                    return;
                }
                final long reasoningStarted=System.currentTimeMillis();
                CloudBrainRouter.requestConsensus(prefs,
                        "Use only the independently opened web evidence below as current external grounding. Compare claims across sources. Prefer primary or official sources when present. If sources disagree, say so. Answer the user's actual question concisely, normally in a few sentences. Do not narrate the research process unless uncertainty matters.\n\nWEB EVIDENCE:\n"+bounded(evText,9000),
                        "",query,new CloudBrainRouter.Callback(){
                    public void onSuccess(String reply,String provider,String model){
                        prefs.edit().putLong("last_web_reasoning_ms",System.currentTimeMillis()-reasoningStarted).apply();
                        cb.onSuccess(reply,evText);
                    }
                    public void onFailure(String error){
                        prefs.edit().putString("last_web_reasoning_error",safe(error)).putLong("last_web_reasoning_ms",System.currentTimeMillis()-reasoningStarted).apply();
                        cb.onSuccess(deterministicEvidenceAnswer(evidenceSet),evText);
                    }
                });
            }catch(Throwable t){cb.onFailure(t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
        },"LumiWebResearch").start();
    }

    private static void recordLookup(SharedPreferences prefs,String q,int count,long ms,float confidence,boolean corroborated,String route){
        prefs.edit().putString("last_web_query",q).putInt("last_web_source_count",count).putInt("last_web_pages_read",count)
                .putFloat("last_web_evidence_confidence",confidence).putBoolean("last_web_independent_corroboration",corroborated)
                .putLong("last_web_retrieval_ms",ms).putString("last_web_lookup_route",route).putLong("last_web_research_at",System.currentTimeMillis()).apply();
    }

    private static String duckDuckGoLookup(String query)throws Exception{
        String raw=get(FreeWebEndpoints.duckDuckGoInstant(query),7000,10000,220000,"application/json,text/plain;q=0.9,*/*;q=0.5");
        JSONObject o=new JSONObject(raw);
        String text=firstNonBlank(o.optString("Answer",""),o.optString("AbstractText",""),o.optString("Definition",""));
        if(text.isBlank()) return null;
        String url=firstNonBlank(o.optString("AbstractURL",""),o.optString("DefinitionURL",""));
        String answer=compactSentences(text,3,650);
        return url.isBlank()?answer:answer+"\nSource: "+url;
    }

    private static final class Lookup{final String text,url;Lookup(String t,String u){text=t;url=u;}}
    private static Lookup wikipediaLookup(String query)throws Exception{
        String raw=get(FreeWebEndpoints.wikipediaOpenSearch(query),7000,10000,180000,"application/json,text/plain;q=0.9,*/*;q=0.5");
        JSONArray a=new JSONArray(raw); if(a.length()<4)return null;
        JSONArray titles=a.optJSONArray(1), urls=a.optJSONArray(3); if(titles==null||titles.length()==0)return null;
        String title=titles.optString(0,""); if(title.isBlank())return null;
        String summaryRaw=get(FreeWebEndpoints.wikipediaSummary(title),7000,10000,220000,"application/json,text/plain;q=0.9,*/*;q=0.5");
        JSONObject s=new JSONObject(summaryRaw); String text=s.optString("extract","");
        String url=urls==null?"":urls.optString(0,"");
        return text.isBlank()?null:new Lookup(text,url);
    }

    private static String deterministicEvidenceAnswer(EvidenceSet set){
        List<Evidence> ranked=set.ranked();
        if(ranked.isEmpty())return "I reached the web but couldn't extract enough reliable text to answer.";
        StringBuilder out=new StringBuilder();
        int limit=Math.min(3,ranked.size());
        for(int i=0;i<limit;i++){
            Evidence e=ranked.get(i);
            String sentence=compactSentences(e.text(),1,320);
            if(sentence.isBlank())continue;
            if(out.length()>0)out.append(" ");
            out.append(sentence);
        }
        if(out.length()==0)return "I checked current sources but couldn't extract a concise reliable answer.";
        if(set.hasIndependentCorroboration()) out.append(" [Checked across ").append(set.size()).append(" independent sources.]");
        else out.append(" [One usable publisher; confidence is limited.]");
        return bounded(out.toString(),950);
    }

    private static String publisher(String url){
        try{String h=new URI(url).getHost();if(h==null)return "unknown";return h.toLowerCase(Locale.US).replaceFirst("^www\\.","");}catch(Exception e){return "unknown";}
    }
    private static double quality(String url){
        String p=publisher(url);
        if(p.endsWith(".gov")||p.endsWith(".mil")||p.endsWith(".edu"))return 0.94;
        if(p.contains("wikipedia.org"))return 0.82;
        if(p.contains("reuters.com")||p.contains("apnews.com")||p.contains("bbc.")||p.contains("npr.org"))return 0.90;
        if(p.contains(".org"))return 0.76;
        return 0.68;
    }

    private static final class ResultLink{final String href,title;ResultLink(String h,String t){href=h;title=t;}}
    private static ArrayList<ResultLink> parseResults(String html){
        ArrayList<ResultLink> out=new ArrayList<>();
        Matcher m=Pattern.compile("<a[^>]*class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html==null?"":html);
        while(m.find()&&out.size()<10){String h=decodeHtml(m.group(1));String t=strip(m.group(2));if(!h.isEmpty()&&!t.isEmpty())out.add(new ResultLink(h,t));}
        return out;
    }
    private static String unwrapDuckDuckGo(String href){
        try{
            String h=href==null?"":href; if(h.startsWith("//"))h="https:"+h;
            URI u=new URI(h); String q=u.getRawQuery();
            if(q!=null){for(String part:q.split("&")){int i=part.indexOf('=');if(i>0&&"uddg".equals(part.substring(0,i)))return URLDecoder.decode(part.substring(i+1),"UTF-8");}}
            return h;
        }catch(Exception e){return href==null?"":href;}
    }
    private static String get(String u,int connect,int read,int maxBytes,String accept)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        try{
            c.setInstanceFollowRedirects(true); c.setConnectTimeout(connect); c.setReadTimeout(read);
            c.setRequestProperty("User-Agent","Lumi/4.1 FunctionalCore"); c.setRequestProperty("Accept",accept);
            int code=c.getResponseCode(); if(code<200||code>=400)throw new IOException("HTTP "+code);
            try(InputStream in=c.getInputStream()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))>0&&b.size()<maxBytes)b.write(x,0,n);return b.toString("UTF-8");}
        }finally{c.disconnect();}
    }
    private static String stripPage(String s){
        if(s==null)return "";
        String x=s.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<noscript.*?</noscript>"," ");
        return decodeHtml(x.replaceAll("(?s)<[^>]+>"," ")).replaceAll("\\s+"," ").trim();
    }
    private static String compactSentences(String s,int maxSentences,int maxChars){
        String clean=(s==null?"":s).replaceAll("\\s+"," ").trim(); if(clean.length()<=maxChars && maxSentences>=8)return clean;
        String[] parts=clean.split("(?<=[.!?])\\s+"); StringBuilder out=new StringBuilder(); int n=0;
        for(String part:parts){String x=part.trim();if(x.isEmpty())continue;if(out.length()+x.length()+1>maxChars)break;if(out.length()>0)out.append(' ');out.append(x);if(++n>=maxSentences)break;}
        if(out.length()==0)return bounded(clean,maxChars); return out.toString();
    }
    private static String strip(String s){return decodeHtml((s==null?"":s).replaceAll("<[^>]+>"," ")).replaceAll("\\s+"," ").trim();}
    private static String decodeHtml(String s){return (s==null?"":s).replace("&amp;","&").replace("&quot;","\\\"").replace("&#x27;","'").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
    private static String firstNonBlank(String...xs){for(String x:xs)if(x!=null&&!x.trim().isEmpty())return x.trim();return "";}
    private static String bounded(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max)+"…";}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
