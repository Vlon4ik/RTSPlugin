package org.vlon4ik.rpsplugin;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.math.Box;

import java.util.*;

import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

import com.mojang.brigadier.arguments.StringArgumentType;

public class Rpsplugin implements ModInitializer {

    private final Map<UUID, UUID> pendingInvites = new HashMap<>();
    private final Map<UUID, UUID> activeGameOpponent = new HashMap<>();
    private final Map<UUID, String> choices = new HashMap<>();

    private final String[] OPTIONS = {"камень", "ножницы", "бумага"};

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /rts — пригласить игрока рядом
            dispatcher.register(literal("rts").executes(context -> {
                ServerPlayerEntity sender = context.getSource().getPlayer();
                UUID senderId = sender.getUuid();

                if (pendingInvites.containsKey(senderId) || activeGameOpponent.containsKey(senderId)) {
                    sender.sendMessage(Text.literal("§cВы уже отправили приглашение или участвуете в игре!"), false);
                    return 1;
                }

                // Поиск игрока в радиусе 10 блоков
                Box box = new Box(sender.getBlockPos()).expand(10);
                List<ServerPlayerEntity> nearby = sender.getWorld()
                        .getEntitiesByClass(ServerPlayerEntity.class, box, p -> !p.getUuid().equals(senderId));

                ServerPlayerEntity target = null;
                for (ServerPlayerEntity p : nearby) {
                    if (!activeGameOpponent.containsKey(p.getUuid()) && !pendingInvites.containsValue(p.getUuid())) {
                        target = p;
                        break;
                    }
                }

                if (target == null) {
                    sender.sendMessage(Text.literal("§cНет свободных игроков поблизости."), false);
                    return 1;
                }

                pendingInvites.put(senderId, target.getUuid());

                target.sendMessage(Text.literal("§b" + sender.getName().getString() + " приглашает сыграть в §3КНБ!"), false);

                MutableText buttons = Text.literal("")
                        .append(Text.literal("§9[Да]").setStyle(
                                Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rts_accept " + senderId))
                        ))
                        .append(Text.literal(" "))
                        .append(Text.literal("§9[Нет]").setStyle(
                                Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rts_decline " + senderId))
                        ));

                target.sendMessage(buttons, false);
                sender.sendMessage(Text.literal("§bВы пригласили игрока §3" + target.getName().getString()), false);
                return 1;
            }));

            // /rts_accept <uuid>
            dispatcher.register(literal("rts_accept")
                    .then(argument("uuid", StringArgumentType.word()).executes(context -> {
                        ServerPlayerEntity accepter = context.getSource().getPlayer();
                        UUID accepterId = accepter.getUuid();
                        UUID senderId = UUID.fromString(StringArgumentType.getString(context, "uuid"));

                        if (!pendingInvites.containsKey(senderId) ||
                                !pendingInvites.get(senderId).equals(accepterId)) {
                            accepter.sendMessage(Text.literal("§cПриглашение не найдено."), false);
                            return 1;
                        }

                        ServerPlayerEntity sender = context.getSource().getServer().getPlayerManager().getPlayer(senderId);
                        if (sender == null) {
                            pendingInvites.remove(senderId);
                            accepter.sendMessage(Text.literal("§cИгрок уже не в сети."), false);
                            return 1;
                        }

                        pendingInvites.remove(senderId);
                        activeGameOpponent.put(senderId, accepterId);
                        activeGameOpponent.put(accepterId, senderId);

                        sender.sendMessage(Text.literal("§b" + accepter.getName().getString() + " §3принял приглашение!"), false);
                        accepter.sendMessage(Text.literal("§3Игра началась!"), false);

                        sendChoiceMenu(sender);
                        sendChoiceMenu(accepter);
                        return 1;
                    })));

            // /rts_decline <uuid>
            dispatcher.register(literal("rts_decline")
                    .then(argument("uuid", StringArgumentType.word()).executes(context -> {
                        ServerPlayerEntity decliner = context.getSource().getPlayer();
                        UUID declinerId = decliner.getUuid();
                        UUID senderId = UUID.fromString(StringArgumentType.getString(context, "uuid"));

                        if (pendingInvites.containsKey(senderId) && pendingInvites.get(senderId).equals(declinerId)) {
                            pendingInvites.remove(senderId);
                            ServerPlayerEntity sender = context.getSource().getServer().getPlayerManager().getPlayer(senderId);
                            if (sender != null) {
                                sender.sendMessage(Text.literal("§b" + decliner.getName().getString() + " §3отклонил приглашение."), false);
                            }
                            decliner.sendMessage(Text.literal("§3Вы отклонили приглашение."), false);
                        } else {
                            decliner.sendMessage(Text.literal("§cПриглашение не найдено."), false);
                        }
                        return 1;
                    })));

            // /rts_choice <option>
            dispatcher.register(literal("rts_choice")
                    .then(argument("option", StringArgumentType.greedyString()).executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        UUID playerId = player.getUuid();
                        String choice = StringArgumentType.getString(context, "option").toLowerCase();

                        if (!activeGameOpponent.containsKey(playerId)) {
                            player.sendMessage(Text.literal("§cВы не в игре."), false);
                            return 1;
                        }
                        if (!Arrays.asList(OPTIONS).contains(choice)) {
                            player.sendMessage(Text.literal("§cНедопустимый выбор."), false);
                            return 1;
                        }

                        if (choices.containsKey(playerId)) {
                            player.sendMessage(Text.literal("§cВы уже сделали выбор!"), false);
                            return 1;
                        }

                        choices.put(playerId, choice);
                        player.sendMessage(Text.literal("§bВы выбрали: §3" + choice), false);

                        UUID opponentId = activeGameOpponent.get(playerId);
                        if (choices.containsKey(opponentId)) {
                            finishGame(context.getSource().getServer().getPlayerManager().getPlayer(playerId),
                                    context.getSource().getServer().getPlayerManager().getPlayer(opponentId));
                        }
                        return 1;
                    })));
        });
    }

    private void sendChoiceMenu(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§3Выберите вариант:"), false);

        for (String opt : OPTIONS) {
            MutableText btn = Text.literal("")
                    .append(Text.literal("§b[" + opt + "]").setStyle(
                            Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rts_choice " + opt))
                    ));
            player.sendMessage(btn, false);
        }
    }


    private void finishGame(ServerPlayerEntity p1, ServerPlayerEntity p2) {
        String c1 = choices.get(p1.getUuid());
        String c2 = choices.get(p2.getUuid());

        String result;
        if (c1.equals(c2)) {
            result = "§bНичья!";
        } else if ((c1.equals("камень") && c2.equals("ножницы")) ||
                (c1.equals("ножницы") && c2.equals("бумага")) ||
                (c1.equals("бумага") && c2.equals("камень"))) {
            result = "§b" + p1.getName().getString() + " §3победил!";
        } else {
            result = "§b" + p2.getName().getString() + " §3победил!";
        }

        p1.sendMessage(Text.literal("§3Вы: §b" + c1 + " §3, соперник: §b" + c2 + ". " + result), false);
        p2.sendMessage(Text.literal("§3Вы: §b" + c2 + " §3, соперник: §b" + c1 + ". " + result), false);

        // очистка
        activeGameOpponent.remove(p1.getUuid());
        activeGameOpponent.remove(p2.getUuid());
        choices.remove(p1.getUuid());
        choices.remove(p2.getUuid());
    }
}
