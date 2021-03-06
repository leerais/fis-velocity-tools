package com.baidu.fis.velocity;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.log.Log;
import org.apache.velocity.runtime.resource.ContentResource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by 2betop on 4/29/14.
 *
 * Fis 资源管理
 */
public class Resource {

    public static final String STYLE_PLACEHOLDER = "<!--FIS_STYLE_PLACEHOLDER-->";
    public static final String SCRIPT_PLACEHOLDER = "<!--FIS_SCRIPT_PLACEHOLDER-->";

    public Boolean debug = false;
    protected String mapDir = null;
    protected String framework = null;
    protected String encoding = "";
    protected int client = 0;

    // 存 map.json 数据， key 为 namespace, value 为 json 数据
    protected Map<String, JSONObject> map;
    protected Map<String, Boolean> loaded;
    protected Map<String, ArrayList<String>> collection;
    protected Map<String, StringBuilder> embed;


    protected Log log;
    protected RuntimeServices rs;

    protected static Map<InternalContextAdapter, Resource> instances = new HashMap<InternalContextAdapter, Resource>();
    public static Resource connect(InternalContextAdapter ctx, RuntimeServices rs) {
        Resource res = instances.get(ctx);

        // System.out.println("enter");

        if (res==null) {
            res = new Resource();
            res.init(rs);
            // System.out.println("New instance" + res);
            instances.put(ctx, res);
        }

        res.client++;

        // System.out.println(res.client);

        return res;
    }

    public static void disConnect(InternalContextAdapter ctx) {
        Resource res = instances.get(ctx);

        if (res==null) {
            return;
        }

        res.client--;

        // System.out.println(res.client);

        if (res.client == 0) {

            // System.out.println("destroy");

            res.destroy();
            instances.remove(ctx);
        }
    }


    public Resource() {
        this.map = new HashMap<String, JSONObject>();
        this.loaded = new HashMap<String, Boolean>();
        this.collection = new HashMap<String, ArrayList<String>>();
        this.embed = new HashMap<String, StringBuilder>();
    }

    public void reset() {
        this.map.clear();
        this.loaded.clear();
        this.collection.clear();
        this.embed.clear();
        this.framework = null;
        this.rs = null;
    }

    public void destroy() {
        this.reset();
    }

    public void init(RuntimeServices rs) {

        // 避免重复初始化
        if (this.rs != null) {
            return;
        }

        this.rs = rs;
        log = rs.getLog();

        // 从velocity.properties里面读取fis.mapDir。
        // 用来指定map文件存放目录。
        mapDir = rs.getString("fis.mapDir", "WEB-INF/config");
        debug = rs.getBoolean("fis.debug", false);

        encoding = (String) rs.getProperty(RuntimeConstants.INPUT_ENCODING);
    }

    public String getMapDir() {
        return mapDir;
    }

    public void setMapDir(String mapDir) {
        this.mapDir = mapDir;
    }

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public void addJS(String id) {
       if (id.contains(":") && !id.contains(":/") && !id.contains(":\\")) {
           this.addResource(id);
       } else {
           String type = "js";
           ArrayList<String> list = collection.get(type);

           if (list == null) {
               list = new ArrayList<String>();
               collection.put(type, list);
           }

           list.add(id);
       }
    }

    public void addJSEmbed(String content) {
        StringBuilder sb = embed.get("js");

        if (sb == null) {
            sb = new StringBuilder();
            embed.put("js", sb);
        }

        sb.append(content);
    }

    public void addCSS(String id) {
        if (id.contains(":") && !id.contains(":/") && !id.contains(":\\")) {
            this.addResource(id);
        } else {
            String type = "css";
            ArrayList<String> list = collection.get(type);

            if (list == null) {
                list = new ArrayList<String>();
                collection.put(type, list);
            }

            list.add(id);
        }
    }

    public void addCSSEmbed(String content) {
        StringBuilder sb = embed.get("css");

        if (sb == null) {
            sb = new StringBuilder();
            embed.put("css", sb);
        }

        sb.append(content);
    }

    public void addResource(String id){
        this.addResource(id, false);
    }

    public void addResource(String id, Boolean deffer) {
        JSONObject map, node, info;

        // 如果添加过了而且添加的方式也相同则不重复添加。（这里说的方式是指，同步 or 异步）
        // 如果之前是同步的这次异步添加则忽略掉。都同步添加过了，不需要异步再添加一次。
        // 注意：null 不能直接用来和 false\true 比较，否则报错。
        if ( loaded.get(id) != null && loaded.get(id) == deffer ||
                deffer && loaded.get(id) != null && !loaded.get(id) ) {
            return;
        }

        map = this.getMap(id);
        node = map.getJSONObject("res");
        info = node.getJSONObject(id);

        String uri;

        if (info == null) {
            throw new IllegalArgumentException("missing resource [" + id + "]");
        }

        String pkg = (String) info.get("pkg");

        if (!debug && pkg != null) {
            node = map.getJSONObject("pkg");
            info = node.getJSONObject(pkg);
            uri = info.getString("uri");

            if (info.containsKey("has")) {
                JSONArray has = info.getJSONArray("has");

                for (Object obj : has) {
                    loaded.put(obj.toString(), deffer);
                }
            }
        } else {
            uri = info.getString("uri");
            loaded.put(id, deffer);
        }


        // 如果有异步依赖，则添加异步依赖
        if (info.containsKey("extras")) {
            node = info.getJSONObject("extras");
            if (node.containsKey("async")) {
                JSONArray async = node.getJSONArray("async");
                for (Object dep : async) {
                    this.addResource(dep.toString(), true);
                }
            }
        }

        // 如果有同步依赖，则把同步依赖也添加进来。
        if (info.containsKey("deps")) {
            JSONArray deps = info.getJSONArray("deps");
            for (Object dep : deps) {
                this.addResource(dep.toString(), deffer);
            }
        }

        String type = info.get("type").toString();

        if (type.equals("js") && deffer) {
            type = "jsDeffer";

            // 如果是异步 js，用 id 代替 uri。因为还要生成依赖。
            // 注意：此处 uri 已不再是 uri。
            uri = id;
        }

        ArrayList<String> list = collection.get(type);

        if (list == null) {
            list = new ArrayList<String>();
            collection.put(type, list);
        }

        list.add(uri);
    }

    protected JSONObject getNode(String id) {
        JSONObject map, node, info;

        map = this.getMap(id);
        node = map.getJSONObject("res");
        info = node.getJSONObject(id);

        if (info == null) {
            throw new IllegalArgumentException("missing resource [" + id + "]");
        }

        String pkg = (String) info.get("pkg");

        if (!debug && pkg != null) {
            node = map.getJSONObject("pkg");
            info = node.getJSONObject(pkg);
        }

        return info;
    }

    public String getUri(String id) {
        return getNode(id).getString("uri");
    }

    public String renderCSS() {
        StringBuilder sb = new StringBuilder();
        ArrayList<String> arr = collection.get("css");

        if (arr != null) {
            for (String uri : arr) {
                sb.append("<link rel=\"stylesheet\" type=\"text/css\" href=\"" + uri + "\"/>");
            }
        }

        StringBuilder embedCSS = embed.get("css");

        if (embedCSS != null) {
            sb.append("<style type=\"text/css\">" + embedCSS.toString() + "</style>");
        }

        return sb.toString();
    }

    public String renderJS() {
        StringBuffer sb = new StringBuffer();
        ArrayList<String> arr = collection.get("js");
        Map<String, Map> defferMap = this.buildDefferMap();

        Boolean needModJs = framework != null && (arr != null && !arr.isEmpty() || !defferMap.isEmpty());
        String modJs = "";

        if (needModJs) {
            modJs = getUri(framework);
            sb.append("<script type=\"text/javascript\" src=\"" + modJs + "\"></script>");
        }

        if (!defferMap.isEmpty()){
            sb.append("<script type=\"text/javascript\">require.resourceMap(" + JSONObject.toJSON(defferMap) + ");</script>");
        }

        if (arr != null) {
            for (String uri : arr) {
                if (uri.equals(modJs)) {
                    continue;
                }
                sb.append("<script type=\"text/javascript\" src=\"" + uri + "\"></script>");
            }
        }

        // 输出 embed js
        StringBuilder embedJS = embed.get("js");

        if (embedJS != null) {
            sb.append("<script type=\"text/javascript\">" + embedJS.toString() + "</script>");
        }

        return sb.toString();
    }

    /**
     * 根据资源的 namespace 读取对应的 fis map 产出表。
     *
     * @param id
     */
    protected JSONObject getMap(String id) {
        String ns = "__global__";
        int pos = id.indexOf(":");

        if (pos != -1) {
            ns = id.substring(0, pos);
        }

        if (!this.map.containsKey(ns)) {
            String filename = mapDir + "/" + (ns.equals("__global__") ? "map.json" : ns + "-map.json");

            try {

                // 通过 velocity 的资源加载器读取内容。
                // 实在是没找到读取 servlet context 的方法，导致定位不到文件。
                // 所以还是用 velocity 的 RuntimeServices 吧
                ContentResource file = rs.getContent(filename, encoding);
                this.map.put(ns, JSONObject.parseObject(file.getData().toString()));

            } catch ( ResourceNotFoundException error ) {
                log.error(error.getMessage());
            }
        }

        JSONObject ret = this.map.get(ns);

        if (ret == null) {
            throw new IllegalArgumentException("missing map json of [" + id + "]");
        }

        return ret;
    }

    /**
     * 生成异步JS资源表。
     * @return
     */
    protected Map<String, Map> buildDefferMap() {
        Map<String, Map> defferMap = new HashMap<String, Map>();
        Map<String, JSONObject> res = new HashMap<String, JSONObject>();
        Map<String, JSONObject> pkgMap = new HashMap<String, JSONObject>();

        ArrayList<String> list = collection.get("jsDeffer");
        JSONObject map, node, info;

        if (list != null) {

            for (String id : list) {

                // 已经同步加载，则忽略。
                if (loaded.get(id) != null && !loaded.get(id)) {
                    continue;
                }

                map = this.getMap(id);
                node = map.getJSONObject("res");
                info = node.getJSONObject(id);

                if (info == null) {
                    throw new IllegalArgumentException("missing resource [" + id + "]");
                }

                // 先加 res
                node = map.getJSONObject("res");
                info = node.getJSONObject(id);
                String pkg = info.getString("pkg");

                JSONObject infoCopy = new JSONObject();
                infoCopy.put("url", info.getString("uri"));

                // 保留 pkg 信息
                if (!debug && pkg != null) {
                    infoCopy.put("pkg", pkg);
                }

                // 过滤掉非 .js 的依赖。
                // 同时过滤掉已经同步加载的依赖。
                if (info.containsKey("deps")) {
                    JSONArray deps = info.getJSONArray("deps");
                    JSONArray depsFilter = new JSONArray();

                    for (Object dep : deps) {
                        String sDep = dep.toString();

                        if (!sDep.endsWith(".js")) {
                            continue;
                        } else if ( loaded.get(sDep) != null && !loaded.get(sDep)) {

                            // 同步中已经依赖。
                            continue;
                        }

                        depsFilter.add(sDep);
                    }

                    if (!depsFilter.isEmpty()) {
                        infoCopy.put("deps", depsFilter);
                    }
                }

                // 再把对应的 pkg 加入。
                if (!debug && pkg != null) {
                    node = map.getJSONObject("pkg");
                    info = node.getJSONObject(pkg);

                    info.put("url", info.getString("uri"));
                    info.remove("uri");

                    pkgMap.put(pkg, info);
                }

                res.put(id, infoCopy);
            }
        }

        if (!res.isEmpty()) {
            defferMap.put("res", res);
        }

        if (!pkgMap.isEmpty()) {
            defferMap.put("pkg", pkgMap);
        }

        return defferMap;
    }

    public String filterContent(String input) {

        if (input.contains(Resource.SCRIPT_PLACEHOLDER)) {
            input = input.replace(Resource.SCRIPT_PLACEHOLDER, renderJS());
        }

        if (input.contains(Resource.STYLE_PLACEHOLDER)) {
            input = input.replace(Resource.STYLE_PLACEHOLDER, renderCSS());
        }

        return input;
    }

}
