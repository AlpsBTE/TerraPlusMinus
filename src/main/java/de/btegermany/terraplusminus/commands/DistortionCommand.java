package de.btegermany.terraplusminus.commands;

import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class DistortionCommand implements BasicCommand {

    @Override
    public void execute(
            @NotNull CommandSourceStack stack,
            @NotNull String[] args
    ) {

        if (!(stack.getSender() instanceof Player player)) {
            stack.getSender().sendMessage(
                    Component.text(
                            "This command can only be used by players.",
                            NamedTextColor.RED
                    )
            );
            return;
        }

        if (!player.hasPermission("t+-.distortion")) {
            player.sendMessage(
                    Component.text(
                            "No permission for /distortion",
                            NamedTextColor.RED
                    )
            );
            return;
        }

        RealWorldGenerator generator =
                Terraplusminus.instance.getActiveGenerator();

        if (generator == null) {
            player.sendMessage(
                    Component.text(
                            "No active Terra+- generator found.",
                            NamedTextColor.RED
                    )
            );
            return;
        }

        try {

            double[] geo = generator.getSettings()
                    .projection()
                    .toGeo(
                            player.getLocation().getX(),
                            player.getLocation().getZ()
                    );

            double[] tissot = generator.getSettings()
                    .projection()
                    .tissot(
                            geo[0],
                            geo[1],
                            0.00001
                    );

            double distortion =
                    Math.sqrt(Math.abs(tissot[0]));

            double angle =
                    Math.toDegrees(tissot[1]);

            player.sendMessage(
                    Component.text()
                            .append(
                                    Component.text(
                                            "Distortion information",
                                            NamedTextColor.GOLD
                                    )
                            )
                            .append(Component.newline())
                            .append(
                                    Component.text(
                                            "Scale factor: ",
                                            NamedTextColor.GRAY
                                    )
                            )
                            .append(
                                    Component.text(
                                            String.format("%.6f blocks/meter", distortion),
                                            NamedTextColor.WHITE
                                    )
                            )
                            .append(Component.newline())
                            .append(
                                    Component.text(
                                            "Angle: ",
                                            NamedTextColor.GRAY
                                    )
                            )
                            .append(
                                    Component.text(
                                            String.format("%.4f°", angle),
                                            NamedTextColor.WHITE
                                    )
                            )
                            .build()
            );

        } catch (OutOfProjectionBoundsException e) {

            player.sendMessage(
                    Component.text(
                            "You are outside the bounds of the active projection.",
                            NamedTextColor.RED
                    )
            );

        }
    }
}
