package de.btegermany.terraplusminus.utils;

import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.file.Path;
import java.util.function.Function;

import static java.nio.file.Files.move;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;


public class PluginConfigManipulator {
    private final Plugin plugin;

    public PluginConfigManipulator(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Remove all lines in plugin's config file that contain the given needle.
     *
     * @param needle the needle to search for in lines
     */
    public void deleteLine(String needle) {
        this.transformLinesContaining(needle, l -> new String[0]);
    }

    /**
     * Add given content above all lines in plugin's config file that contain the given needle.
     *
     * @param needle the needle to search for in lines
     */
    public void addLineAbove(String needle, String content) {
        this.transformLinesContaining(needle, l -> new String[] {content, l});
    }

    /**
     * Add given content below all lines in plugin's config file that contain the given needle.
     *
     * @param needle the needle to search for in lines
     */
    public void addLineBelow(String needle, String content) {
        this.transformLinesContaining(needle, l -> new String[] {l, content});
    }

    private void transformLinesContaining(String needle, Function<@NonNull String, @NonNull String[]> transformer) {
        Path inputFile = this.plugin.getDataPath().resolve("config.yml");
        Path tempFile = this.plugin.getDataPath().resolve("temp.yml");
        try (
                BufferedReader reader = new BufferedReader(new FileReader(inputFile.toFile()));
                BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile.toFile()))
        ) {

            String currentLine;
            while ((currentLine = reader.readLine()) != null) {
                if (!currentLine.contains(needle)) {
                    writer.write(currentLine + "\n");
                } else {
                    String[] transformed = transformer.apply(currentLine);
                    for (String line: transformed) {
                        writer.write(line + "\n");
                    }
                }
            }

            move(tempFile, inputFile, ATOMIC_MOVE);
        } catch (IOException e) {
            this.plugin.getComponentLogger().error("Failed to transform config file {} with needle '{}'", inputFile, needle, e);
        }
    }

}
