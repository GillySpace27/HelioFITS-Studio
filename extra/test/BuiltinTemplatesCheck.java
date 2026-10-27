package org.helioviewer.jhv.app.state;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.display.Pizza;
import org.json.JSONObject;

/**
 * Every built-in template listed in resources/templates/templates.txt loads (HS-25): the file is
 * there, its template object parses, its thumbnail is a readable PNG, and a pizza in it restores
 * on its own layers. A built-in that fails to parse is only logged and left out of the list, so
 * without this a broken one would simply vanish from File > New from Template.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.state.BuiltinTemplatesCheck
 */
public final class BuiltinTemplatesCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        List<String> listed = new ArrayList<>();
        try (InputStream in = BuiltinTemplatesCheck.class.getResourceAsStream("/templates/templates.txt")) {
            expect("templates.txt is on the class path", in != null);
            if (in != null)
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                    if (!line.isBlank() && !line.strip().startsWith("#"))
                        listed.add(line.strip());
        }
        expect("at least one built-in is listed: " + listed, !listed.isEmpty());

        List<TemplateLibrary.Entry> all = TemplateLibrary.all();
        for (String name : listed) {
            String key = name.substring(0, name.length() - 4);
            TemplateLibrary.Entry e = all.stream().filter(x -> x.builtIn() && x.key().equals(key)).findFirst().orElse(null);
            expect(name + " is listed as a built-in template", e != null);
            if (e == null)
                continue;
            expect(name + " has a name and a description", !e.meta().name().isBlank() && !e.meta().description().isBlank());
            try (InputStream png = BuiltinTemplatesCheck.class.getResourceAsStream("/templates/" + e.meta().thumbnail())) {
                expect(name + " has its thumbnail " + e.meta().thumbnail(), png != null && ImageIO.read(png) != null);
            }
            JSONObject pizza = e.state().optJSONObject("pizza");
            if (pizza != null) {
                Pizza.fromJson(pizza, List.of());
                expect(name + "'s pizza restores as " + pizza.optString("shape"), Pizza.isOn()
                        && Pizza.getShape().name().equals(pizza.optString("shape")));
                Pizza.fromJson(null, List.of());
            }
        }

        System.out.println(failures == 0 ? "BuiltinTemplatesCheck: PASS" : "BuiltinTemplatesCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private BuiltinTemplatesCheck() {}

}
