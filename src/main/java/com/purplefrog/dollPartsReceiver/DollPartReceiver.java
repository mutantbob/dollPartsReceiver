package com.purplefrog.dollPartsReceiver;

import com.google.common.collect.*;
import com.purplefrog.apachehttpcliches.*;
import com.purplefrog.httpcliches.*;
import org.apache.commons.fileupload.*;
import org.apache.http.*;
import org.apache.http.entity.*;
import org.apache.http.protocol.*;
import org.apache.log4j.*;
import org.json.*;
import org.stringtemplate.v4.*;

import javax.jws.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

public class DollPartReceiver
    implements HttpRequestHandler
{
    private static final Logger logger = Logger.getLogger(DollPartReceiver.class);

    public final String prefix;

    File master;

    private List<Sprite> sprites= new ArrayList<>();

    public DollPartReceiver(String prefix, File partsRootDir)
    {
        this.prefix = prefix;
        master = partsRootDir;

        scanKnownSprites();
    }

    private void scanKnownSprites()
    {
        scanKnownSprites(master, "");
    }

    private void scanKnownSprites(File dir, String relativePath)
    {
        File[] entries = dir.listFiles();

        if (entries==null)
            return;

        for (File file : entries) {
            if (file.isDirectory()) {
                String rp2 = (relativePath.length()> 0 ? relativePath + "/" : "")+ file.getName();
                scanKnownSprites(file, rp2);
            } else {
                String basename = file.getName().toLowerCase();
                Integer idx = getIndexFromPNGName(basename);
                if (idx != null) {
                    try {
                        Sprite sprite = new Sprite(idx, dir, "image/png");
                        sprites.add(sprite);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static Integer getIndexFromPNGName(String basename)
    {
        if (basename.toLowerCase().endsWith(".png"))
            return Integer.parseInt(basename.substring(0, basename.length() - 4));
        else
            return null;
    }

    public String relativeDirFor(File imageDir)
    {
        String parent = imageDir.getPath();
        String masterPath = master.getPath()+"/";
        if (parent.startsWith(masterPath)) {
            return parent.substring(masterPath.length());
        }
        return parent;
    }

    public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext)
    {
        EntityAndHeaders result;

        try {
            URI uri = new URI(httpRequest.getRequestLine().getUri());
            if (!uri.getPath().startsWith(prefix)) {
                result = EntityAndHeaders.plainTextPayload(404, "not found");
            } else {
                String suffix = uri.getPath().substring(prefix.length());

                result = handle_(httpRequest, httpContext, suffix);
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("malfunction processing web request", e);
            result = EntityAndHeaders.plainTextPayload(500, "I am full of explosions:\n"+ Util2.stringStackTrace(e));
            allowXSite(result);
        }

        result.apply(httpResponse);

    }

    private EntityAndHeaders handle_(HttpRequest req, HttpContext ctx, String suffix)
        throws IOException, FileUploadException, URISyntaxException, CGIWebMethod.CGISOAPTransformException, InvocationTargetException, IllegalAccessException, JSONException
    {
        if ("".equals(suffix)) {
            return new EntityAndHeaders.Redirect(ApacheHTTPCliches.redirectPath(ctx, prefix+"/"), "moved");
        }
        if ("/".equals(suffix)) {
            return rootPage();
        }

  /*      if ("/experiment".equals(suffix)) {
            String method = req.getRequestLine().getMethod();
            CGIEnvironment env = ApacheCGI.parseEnv(req, ctx);
            System.out.println(env);
            List<Object> a = env.args.get("fileUploadObjects");
            return experiment(a.get(0).toString());
        }*/

        {
            // commence extremely magical bullshit
            Method m = CGIWebMethod.matchName(getClass(), suffix.substring(1));
            if (null != m) {
                CGIEnvironment cgiEnv = ApacheCGI.parseEnv(req, ctx);
                Object payload = m.invoke(this, CGIWebMethod.transformCGIArgumentsToJavaParams(m, cgiEnv));
                if (payload instanceof EntityAndHeaders) {
                    return (EntityAndHeaders) payload;
                } else {
                    return EntityAndHeaders.plainTextPayload(200, payload.toString());
                }
            }
        }

        return EntityAndHeaders.plainTextPayload(404, "Not Found");
    }

    public EntityAndHeaders rootPage()
        throws IOException
    {
        InputStream istr = getClass().getResourceAsStream("dollPartsReceiver.html");
        String html = Util2.slurp(new InputStreamReader(istr));

        Collections.sort(sprites);

        ST st = new ST(HTMLEnabledObject.makeSTGroup(true, '$', '$'), html);
        Map<File, List<Sprite>> map = sprites.stream().collect(Collectors.groupingBy(o -> o.dir));
        st.add("sprites", map.values());

        return EntityAndHeaders.plainPayload(200, st.render(), "text/html");
    }

    @WebMethod
    public EntityAndHeaders receiveDollPart(@WebParam(name="image")FileItem imagePtr)
        throws IOException
    {
        String ofname = "/tmp/dollPart.png";
        FileOutputStream ostr = new FileOutputStream(ofname);

        InputStream istr = imagePtr.getInputStream();

        long count=0;
        byte[] tmp = new byte[4<<10];
        while (true) {
            int n = istr.read(tmp);
            if (n<1)
                break;
            ostr.write(tmp, 0, n);
            count += n;
        }

        ofname = "dollPart.png";
        return EntityAndHeaders.plainTextPayload(200, "wrote "+count+" bytes to "+ofname);
    }

    @WebMethod
    public EntityAndHeaders dollPart()
    {
        HttpEntity en = new FileEntity(new File("/tmp/dollPart.png"));

        return new EntityAndHeaders(200, en);
    }

    @WebMethod
    public synchronized EntityAndHeaders experiment(@WebParam (name="fileUploadObjects") String json,
                                                    @WebParam(name="name")String contributor,
                                                    @WebParam(name="website")String webSite )
        throws JSONException, IOException
    {
        logger.trace("somebody gave me a \n"+json+"\n\n");

        JSONArray a = new JSONArray(json);

        StringBuilder payload = new StringBuilder("<html><body>\n");
        StringBuilder errors = new StringBuilder();
        List<Sprite> toAdd = new ArrayList<>();
        boolean anyFails = false;
        for (int i=0; i<a.length(); i++) {
            try {
                Sprite sprite = absorbSprite(a.getJSONObject(i));
                toAdd.add(sprite);
                payload.append("<img src=\"images?dir="+sprite.dir+"&idx="+sprite.imageNumber+"\">\n");
            } catch (Exception e) {
                e.printStackTrace();
                errors.append(Util2.stringStackTrace(e)+"\n");
                anyFails=true;
            }
        }

        EntityAndHeaders rval;
        if (anyFails) {
            for (Sprite rollback : toAdd) {
                rollback.getFile().delete();
            }
            rval = EntityAndHeaders.plainPayload(500, errors.toString(), "text/plain");
            return rval;
        } else {
            addCredits(contributor, webSite);
            sprites.addAll(toAdd);

            payload.append("<pre>"+errors+"</pre>\n</body></html>\n");

            if (false) {
                return EntityAndHeaders.plainPayload(500, payload.toString(), "text/html");
            }

            int status = errors.length()==0 ? 200 : 500;
            rval = EntityAndHeaders.plainPayload(status, payload.toString(), "text/plain");
        }

        allowXSite(rval);
        return rval;
    }

    public void addCredits(String contributor, String webSite)
        throws IOException
    {
        File f= creditsFile();
        FileWriter fw = new FileWriter(f, true);
        fw.write(contributor+",\t"+webSite+"\n");
        fw.close();
    }

    public File creditsFile()
    {
        return new File(master, "credits.txt");
    }

    static Pattern imgBlob = Pattern.compile("^data:(.*?);base64,");

    public Sprite absorbSprite(JSONObject row)
        throws JSONException, IOException
    {
        String dir = row.getString("directory");
        if (dir.contains(".."))
            throw new IOException("nope");

        int maxImageNumberKnown = row.getInt("maxImageNumberKnown");
        String data = row.getString("data");

        int idx = pickIndex(dir, maxImageNumberKnown+1);

        Matcher m = imgBlob.matcher(data);

        if (m.find()) {
            String png = data.substring(m.end());
            return new Sprite(idx, Base64.getDecoder().decode(png), m.group(1), fileFor(dir));
        }
        return null;
    }

    public int pickIndex(String path, int maybe)
    {
        File dir = new File(master, path);

        File[] entries =dir.listFiles();

        if (entries==null || entries.length==0)
            return maybe;

        int max=1;
        for (File file : entries) {
            Integer idx = getIndexFromPNGName(file.getName());
            if (idx!=null && idx>max)
                max = idx;
        }
        return max+1;
    }

    @WebMethod
    public EntityAndHeaders image(@WebParam(name="dir") String dir,
                                  @WebParam(name="idx") int idx)
    {
        for (Sprite sprite : sprites) {
            if (sprite.getRelativeURL().equals(dir)
                && idx == sprite.imageNumber) {
                HttpEntity en = new FileEntity(sprite.getFile(), ContentType.create(sprite.mime));
                return new EntityAndHeaders(200, en);
            }
        }
        return  EntityAndHeaders.plainTextPayload(404, "Not Found");
    }

    public static void allowXSite(EntityAndHeaders rval)
    {
        rval.addHeader("Access-Control-Allow-Origin", "*");
    }

    public File fileFor(String dir)
    {
        return new File(master, dir);
    }

    public static void main(String[] argv)
        throws IOException
    {
        BasicConfigurator.configure();

        LogManager.getLogger(HTMLTools.class).setLevel(Level.INFO);

        HttpRequestHandlerRegistry registry = new HttpRequestHandlerRegistry();

        int port = 4046;
        {
            String portKey = "dollParts.port";
            String port_ = System.getProperty(portKey);
            if (port_==null) {
                System.out.println("no -D"+portKey+"= ; using default of "+port);
            } else {
                port = Integer.parseInt(port_);
            }
        }
        BasicHTTPAcceptLoop loop = new BasicHTTPAcceptLoop(port, registry, Executors.newCachedThreadPool());

        File partsRootDir = new File("/var/spool/dollparts/");
        {
            String rootKey = "dollParts.directory";
            String dir_ = System.getProperty(rootKey);
            if (dir_==null) {
                System.out.println("no -D"+rootKey+"= ; using default of "+partsRootDir);
            } else {
                partsRootDir = new File(dir_);
            }
        }
        DollPartReceiver dollPR = new DollPartReceiver("", partsRootDir);
        registry.register(dollPR.prefix+"*", dollPR);

        System.out.println("accepting connections at http://"+ loop.getAddress());
        loop.run();
    }


    public class Sprite
        implements Comparable<Sprite>
    {

        public final File dir;
        public final int imageNumber;
        public String mime;

        public Sprite(int imageNumber, byte[] png, String mime, File dir1)
            throws IOException
        {
            this.imageNumber = imageNumber;
            this.dir = dir1;
            this.mime = mime;

            File f = getFile();
            FileOutputStream fw = new FileOutputStream(f);
            fw.write(png);
            fw.flush();
        }

        public Sprite(int imageNumber, File dir1, String mime)
        {
            this.imageNumber = imageNumber;
            this.dir = dir1;
            this.mime = mime;
        }

        public String getRelativeURL()
        {
            return relativeDirFor(dir);
        }

        public File getFile()
        {
            return new File(this.dir, imageNumber+".png");
        }

        @Override
        public int compareTo(Sprite arg)
        {
            return ComparisonChain.start()
                .compare(dir, arg.dir)
                .compare(arg.imageNumber, imageNumber)
                .result();
        }
    }

}
