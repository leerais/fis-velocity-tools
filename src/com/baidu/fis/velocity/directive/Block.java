package com.baidu.fis.velocity.directive;

import org.apache.velocity.context.InternalContextAdapter;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.log.Log;
import org.apache.velocity.runtime.parser.node.Node;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;

/**
 * Created by 2betop on 5/7/14.
 */
public class Block extends AbstractBlock {
    protected static Map<String, Map<String, Node>> blocks = new HashMap<String, Map<String, Node>>();
    protected static Stack<String> templates = new Stack<String>();

    public static void pushTemplate(String template) {
        templates.push(template);
    }

    public static String popTemplate() {
        if (templates.isEmpty()) {
            return "";
        }
        return templates.pop();
    }

    public static String getCurrentTemplate() {
        if (templates.isEmpty()) {
            return "";
        }
        return templates.peek();
    }

    public static void registerBlocks(String templateName, Map<String, Node> map) {
        blocks.put(templateName, map);
    }

    public static void unRegisterBlocks(String templateName) {
        blocks.remove(templateName);
    }


    @Override
    public String getName() {
        return "block";
    }

    @Override
    public boolean render(InternalContextAdapter context, Writer writer, Node node) throws IOException, ResourceNotFoundException, ParseErrorException, MethodInvocationException {

        if ( node.jjtGetNumChildren() == 0 )
        {
            throw new VelocityException("#block(): argument missing at " +
                    Log.formatFileString(this));
        }

        String id = node.jjtGetChild(0).value(context).toString();

        if ( id.isEmpty() ) {
            throw new VelocityException("#block(): the first argument is empty ");
        }


        String templateName = getCurrentTemplate();
        Map<String, Node> map = blocks.get(templateName);
        Boolean overrated = false;
        if (map != null) {

            Node extend = map.get(id);
            if (extend != null) {
                overrated = true;
                map.remove(id);
                popTemplate();
                extend.render(context, writer);
                pushTemplate(templateName);
            }
        }

        if (!overrated) {
            Node block = node.jjtGetChild(node.jjtGetNumChildren() - 1);
            block.render(context, writer);
        }

        return true;
    }
}
