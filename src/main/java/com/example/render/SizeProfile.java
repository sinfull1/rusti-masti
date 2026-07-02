package com.example.render;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * One size variant, bound from {@code render.sizes.<name>.*}. Each has its own template (layout/CSS)
 * and output geometry, so a request can be rendered small / medium / large / xlarge. Micronaut creates
 * one bean per key under {@code render.sizes}; the key becomes {@link #getName()}.
 */
@EachProperty("render.sizes")
public class SizeProfile {

    private final String name;
    private String template;        // full standalone Thymeleaf template for this size
    private int width = 480;         // CSS px; should match the template's body width
    private double deviceScale = 2.0;
    private boolean autoHeight = true;
    private int height = 1200;       // used only when autoHeight is false

    public SizeProfile(@Parameter String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public double getDeviceScale() { return deviceScale; }
    public void setDeviceScale(double deviceScale) { this.deviceScale = deviceScale; }

    public boolean isAutoHeight() { return autoHeight; }
    public void setAutoHeight(boolean autoHeight) { this.autoHeight = autoHeight; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
}
