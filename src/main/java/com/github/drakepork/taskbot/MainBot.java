package com.github.drakepork.taskbot;

import org.apache.commons.text.WordUtils;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.PrivateChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.MessageFlag;
import org.javacord.api.entity.message.component.ActionRow;
import org.javacord.api.entity.message.component.Button;
import org.javacord.api.entity.message.component.SelectMenu;
import org.javacord.api.entity.message.component.SelectMenuOption;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.user.User;
import org.javacord.api.interaction.*;
import org.javacord.api.util.logging.FallbackLoggerConfiguration;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;


public class MainBot {
    private static DatabaseHook db;
    private static DiscordApi api;
    private static final HashMap<String, List<Long>> tasksPinged = new HashMap<>();
    private static List<String> getOrder(String taskId) {
        List<String> order = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT order_list FROM tasks WHERE task_name = ?")) {
            ps.setString(1, taskId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                order.addAll(List.of(rs.getString(1).split(",")));
            }
        } catch (SQLException e) {
            System.err.println("Couldn't get order list from database! Exiting...");
            System.exit(1);
        }
        return order;
    }
    private static void setOrder(String taskId, List<String> order) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement("UPDATE tasks SET order_list = ? WHERE task_name = ?")) {
            ps.setString(1, String.join(",", order));
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Couldn't update order list in database! Exiting...");
            System.exit(1);
        }
    }
    private static List<String> randomizedOrder(String excluding) {
        List<String> order = new ArrayList<>();
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT discord FROM people WHERE discord <> ?")) {
            ps.setLong(1, Long.parseLong(excluding));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                order.add(String.valueOf(rs.getLong(1)));
            }
        } catch (SQLException e) {
            System.err.println("Couldn't get discord ids from database! Exiting...");
            System.exit(1);
        }
        Collections.shuffle(order);
        return order;
    }
    private static void setNextPerson(String taskId, boolean giveScore) {
        if(giveScore) updateScore(getCurrentPerson(taskId), 1);
        List<String> order = getOrder(taskId);
        if(taskId.equalsIgnoreCase("rydde")) {
            if(giveScore) updateScore(getSecondPerson(taskId), 1);
            order.remove(0);
            order.remove(0);
            if(order.size() == 1) {
                order.addAll(randomizedOrder(order.get(0)));
            }
        } else {
            String first = order.get(0);
            order.remove(first);
            order.add(first);
        }
        setOrder(taskId, order);
        tasksPinged.remove(taskId);
    }
    private static String getName(Long person) {
        String name = "";
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT person_name FROM people WHERE discord = ?")) {
            ps.setLong(1, person);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                name = rs.getString(1);
            }
        } catch (SQLException e) {
            System.err.println("Couldn't get person's name from database! Exiting...");
            System.exit(1);
        }
        return name;
    }
    private static long getCurrentPerson(String taskId) {
        return Long.parseLong(getOrder(taskId).get(0));
    }
    private static long getSecondPerson(String taskId) {
        return Long.parseLong(getOrder(taskId).get(1));
    }
    private static void setNextPing(String taskId, long interval) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET next_ping = ? WHERE task_name = ?")) {
            ps.setLong(1, interval);
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Couldn't set next ping in database! Exiting...");
            System.exit(1);
        }
    }
    private static void autoPingTasksTimer() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                Calendar cal = Calendar.getInstance();
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                int minute = cal.get(Calendar.MINUTE);
                boolean general = hour == 10 && minute < 4;
                boolean dunkene = hour == 17 && minute < 4;
                if(general || dunkene) {
                    HashMap<String, List<Object>> tasks = new HashMap<>();
                    try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                            "SELECT task_name, message_description, next_ping, ping_interval, order_list FROM tasks")) {
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            long nextPing = rs.getLong(3);
                            if(nextPing != 0) {
                                List<Object> taskData = new ArrayList<>();
                                taskData.add(rs.getString(2)); // msg 0
                                taskData.add(nextPing); // next ping 1
                                taskData.add(rs.getLong(4)); // interval 2
                                taskData.add(rs.getString(5)); // order 3
                                tasks.put(rs.getString(1), taskData);
                            }
                        }
                    } catch (SQLException e) {
                        System.err.println("Couldn't get tasks from database! Exiting...");
                        System.exit(1);
                    }
                    if(!tasks.isEmpty()) {
                        Calendar today = Calendar.getInstance();
                        tasks.forEach((task, data) -> {
                            boolean isGeneral = !task.toLowerCase().contains("dunkene");
                            if((isGeneral && general) || (!isGeneral && dunkene)) {
                                if (!tasksPinged.containsKey(task)) {
                                    Calendar pingDay = Calendar.getInstance();
                                    pingDay.setTime(new Date((long) data.get(1)));
                                    if (today.get(Calendar.DAY_OF_YEAR) == pingDay.get(Calendar.DAY_OF_YEAR)) {
                                        long person = getCurrentPerson(task);
                                        String personName = getName(person);
                                        if (!personName.isEmpty()) {
                                            pingTask(task);
                                        }
                                    }
                                } else if(isGeneral) {
                                    long nextPing = (long) data.get(1);
                                    if(System.currentTimeMillis() > nextPing) {
                                        long person = getCurrentPerson(task);
                                        updateScore(person, -5);
                                        if(task.equalsIgnoreCase("rydde")) {
                                            long secPerson = getSecondPerson(task);
                                            updateScore(secPerson, -5);
                                        }
                                        long interval = (long) data.get(2);
                                        setNextPing(task, System.currentTimeMillis() + interval);
                                    }
                                }
                            }
                        });
                    }
                }
            }
        }, 0, TimeUnit.SECONDS.toMillis(30));
    }
    private static void updateScore(long person, int amount) {
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE people SET score = score + ? WHERE discord = ?")) {
            ps.setInt(1, amount);
            ps.setLong(2, person);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Couldn't update score in database! Exiting...");
            System.exit(1);
        }
    }
    private static void pingedTasksTimer() {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                if(!tasksPinged.isEmpty()) {
                    for(String task : tasksPinged.keySet()) {
                        List<Long> pingData = tasksPinged.get(task);
                        long pingedAt = pingData.get(0);
                        long pingTime = TimeUnit.HOURS.toMillis(8) + pingedAt;
                        boolean isDunker = task.toLowerCase().contains("dunkene");
                        if(isDunker) pingTime = TimeUnit.MINUTES.toMillis(15) + pingedAt;

                        if (System.currentTimeMillis() > pingTime) {
                            try {
                                long person = getCurrentPerson(task);
                                User user = api.getUserById(person).get();
                                if(user.getPrivateChannel().isPresent()) {
                                    long messageId = pingData.get(1);
                                    PrivateChannel channel = user.getPrivateChannel().get();
                                    Message message = channel.getMessageById(messageId).get();
                                    MessageBuilder newMessage = new MessageBuilder();
                                    newMessage.copy(message);
                                    message.delete();
                                    Message sentMessage = newMessage.send(user).get();
                                    List<Long> newPingData = new ArrayList<>();
                                    newPingData.add(System.currentTimeMillis());
                                    newPingData.add(sentMessage.getId());
                                    newPingData.add(pingData.get(2));
                                    boolean teams = task.equalsIgnoreCase("rydde");
                                    if(!isDunker) updateScore(person, -1);
                                    if(teams) {
                                        long secPerson = getSecondPerson(task);
                                        updateScore(secPerson, -1);
                                        user = api.getUserById(secPerson).get();
                                        if(user.getPrivateChannel().isPresent()) {
                                            messageId = pingData.get(2);
                                            channel = user.getPrivateChannel().get();
                                            message = channel.getMessageById(messageId).get();
                                            newMessage = new MessageBuilder();
                                            newMessage.copy(message);
                                            message.delete();
                                            sentMessage = newMessage.send(user).get();
                                            newPingData.add(sentMessage.getId());
                                        }
                                    }
                                    tasksPinged.put(task, newPingData);

                                    if(isDunker) {
                                        ZonedDateTime firstPinged = Instant.ofEpochMilli(pingData.get(2))
                                                .atZone(ZoneId.systemDefault());
                                        ZonedDateTime now = ZonedDateTime.now();

                                        long hoursSinceFirstPinged = ChronoUnit.HOURS.between(firstPinged, now);
                                        if(hoursSinceFirstPinged >= 18) {
                                            updateScore(person, -10);
                                            setNextPerson(task, false);
                                        }
                                    }
                                }
                            } catch (InterruptedException | ExecutionException e) {
                                System.err.println("Something went wrong when trying to repeat a task ping! Exiting...");
                                System.exit(1);
                            }
                        }
                    }
                }
            }
        }, 0, TimeUnit.SECONDS.toMillis(30));
    }
    private static boolean canUseCommands(long person) {
        return !getName(person).isEmpty();
    }
    public static String pingTask(String task) {
        String secName = "";
        try {
            long person = getCurrentPerson(task);
            String personName = getName(person);
            User user = api.getUserById(person).get();
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("-= " + task.replace("_", " ").toUpperCase() + " TIME =-");
            boolean teams = task.equalsIgnoreCase("rydde");
            String desc = "";
            User secUser = null;
            if(teams) {
                long secPerson = getSecondPerson(task);
                secName  = getName(secPerson);
                secUser = api.getUserById(secPerson).get();
            }
            long interval = 0;
            try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "SELECT message_description, ping_interval FROM tasks WHERE task_name = ?")) {
                ps.setString(1, task);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    desc = rs.getString(1);
                    interval = rs.getLong(2);
                    embed.setDescription(desc + (teams ? "\n\nDin lagkamerat er: " + secName : ""));
                }
            } catch (SQLException e) {
                System.err.println("Couldn't get task from database! Exiting...");
                System.exit(1);
            }
            MessageBuilder msg = new MessageBuilder();
            msg.setEmbed(embed);
            msg.addComponents(ActionRow.of(Button.success(task, "Trykk når du er ferdig")));
            Message sentMessage = msg.send(user).get();
            List<Long> pingData = new ArrayList<>();
            pingData.add(System.currentTimeMillis());
            pingData.add(sentMessage.getId());
            pingData.add(System.currentTimeMillis());
            if(teams) {
                embed.setDescription(desc + "\n\nDin lagkamerat er: " + personName);
                msg.setEmbed(embed);
                pingData.add(msg.send(secUser).get().getId());
            }
            tasksPinged.put(task, pingData);
            if(interval != 0) {
                setNextPing(task, System.currentTimeMillis() + interval);
            }
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Something went wrong when pinging a task! Exiting...");
            System.exit(1);
        }
        return secName;
    }
    private static void generateConfig(File configFile) {
        Properties defaultConfig = getDefaultProperties();
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            defaultConfig.store(fos, "Default config values");
            System.out.println("Successfully created the config file!");
        } catch (IOException e) {
            System.err.println("Error creating the config file! Exiting...");
            System.exit(1);
        }
    }
    private static void sendStatus(SlashCommandInteraction slashInt) {
        StringBuilder desc = new StringBuilder();
        HashMap<String, List<Object>> tasks = new HashMap<>();
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT task_name, message_description, next_ping, ping_interval, order_list FROM tasks")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                List<Object> taskData = new ArrayList<>();
                taskData.add(rs.getString(2)); // msg 0
                taskData.add(rs.getLong(3)); // next ping 1
                taskData.add(rs.getLong(4)); // interval 2
                taskData.add(rs.getString(5)); // order 3
                tasks.put(rs.getString(1), taskData);
            }
        } catch (SQLException e) {
            System.err.println("Couldn't get tasks from database! Exiting...");
            System.exit(1);
        }

        tasks.forEach((task, data) -> {
            String personName = getName(getCurrentPerson(task));
            boolean isPinged = tasksPinged.containsKey(task);

            if(!personName.isEmpty()) {
                desc.append("**").append(WordUtils.capitalize(task.replace("_", " "))).append("**\n")
                        .append("- Er Pinget: ").append(isPinged ? "Ja" : "Nei").append("\n")
                        .append("- ").append(isPinged ? "Pinget" : "Neste").append(" Person: ").append(personName).append("\n");
                if(isPinged) {
                    long lastPinged = tasksPinged.get(task).get(0);
                    long nextPing = lastPinged + (task.contains("dunkene") ? TimeUnit.MINUTES.toMillis(15) : TimeUnit.HOURS.toMillis(8));
                    LocalDateTime curr = LocalDateTime.now();
                    LocalDateTime next = LocalDateTime.ofInstant(Instant.ofEpochMilli(nextPing), ZoneId.systemDefault());
                    long minutesTill = curr.until(next, ChronoUnit.MINUTES);
                    long hoursTill = (minutesTill / 60);
                    long minsTill = (minutesTill % 60);
                    LocalDateTime prev = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPinged), ZoneId.systemDefault());
                    long minutesSince = prev.until(curr, ChronoUnit.MINUTES);
                    long hoursSince = (minutesSince / 60);
                    long minsSince = (minutesSince % 60);
                    String timeTill = (minutesTill >= 60 ? hoursTill + " time" + (hoursTill != 1 ? "r, " : ", ") + minsTill + " minutt" + (minsTill != 1 ? "er" : "") : minutesTill + " minutt" + (minutesTill != 1 ? "er" : ""));
                    String timeSince = (minutesSince >= 60 ? hoursSince + " time" + (hoursSince != 1 ? "r, " : ", ") + minsSince + " minutt" + (minsSince != 1 ? "er" : "") : minutesSince + " minutt" + (minutesSince != 1 ? "er" : ""));
                    desc.append("- Sist Pinget: ").append(timeSince).append(" siden").append("\n")
                            .append("- Neste Ping: ").append("Om ").append(timeTill).append("\n");
                }
                long autoPing = (long) data.get(1);
                if(autoPing != 0) {
                    LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(autoPing), ZoneId.systemDefault());
                    int day = dateTime.getDayOfMonth();
                    String month = dateTime.getMonth().getDisplayName(TextStyle.FULL, new Locale("no"));
                    desc.append("- Neste Auto Ping: ").append(day).append(" ").append(month).append("\n");
                }
            }
        });

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Status på Tasks");
        embed.setDescription(desc.toString());
        slashInt.createImmediateResponder()
                .addEmbed(embed)
                .setFlags(MessageFlag.EPHEMERAL)
                .respond();
    }
    private static Properties getDefaultProperties() {
        Properties defaultConfig = new Properties();
        defaultConfig.setProperty("database.ip", "");
        defaultConfig.setProperty("database.port", "");
        defaultConfig.setProperty("database.name", "");
        defaultConfig.setProperty("database.user", "");
        defaultConfig.setProperty("database.password", "");
        defaultConfig.setProperty("discord.token", "");
        return defaultConfig;
    }
    public static void main(String[] args) {
        Properties config = new Properties();
        String externalConfigPath = "./config.properties";

        File configFile = new File(externalConfigPath);
        if (!configFile.exists()) {
            generateConfig(configFile);
        }

        try (FileInputStream fileStream = new FileInputStream(externalConfigPath)) {
            config.load(fileStream);
        } catch (IOException e) {
            System.err.println("Config file doesn't exist or couldn't be loaded! Exiting...");
            System.exit(1);
        }
        String token = config.getProperty("discord.token");
        if(token == null || token.isEmpty()) {
            System.err.println("Discord token not set! Exiting...");
            System.exit(1);
        }
        api = new DiscordApiBuilder()
                .setToken(token)
                .login().join();
        if(api == null) {
            System.err.println("Failed to connect to Discord! Exiting...");
            System.exit(1);
        }

        db = new DatabaseHook(config);

        FallbackLoggerConfiguration.setDebug(true);
        FallbackLoggerConfiguration.setTrace(true);
        pingedTasksTimer();
        autoPingTasksTimer();

        SlashCommand.with("pingtask", "pinge tasks")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createGlobal(api)
                .join();
        SlashCommand.with("pingstatus", "Sjekke status på tasks")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createGlobal(api)
                .join();

        api.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction slashInt = event.getSlashCommandInteraction();
            User commandUser = slashInt.getUser();

            if (canUseCommands(commandUser.getId())) {
                String cmd = slashInt.getCommandName().toLowerCase();
                if(cmd.equals("pingtask")) {
                    slashInt.createImmediateResponder()
                        .setContent("Velg hvilke tasks du vil pinge:")
                        .addComponents(
                            ActionRow.of(SelectMenu.createStringMenu("tasks", "Klikk for å vise tasks", 1, 1,
                                Arrays.asList(SelectMenuOption.create("Restavfall", "restavfall", "Klikk her for å pinge Restavfall Task"),
                                    SelectMenuOption.create("Papp", "papp", "Klikk her for å pinge Papp Task"),
                                    SelectMenuOption.create("Metall", "metall", "Klikk her for å pinge Metall Task"),
                                    SelectMenuOption.create("Matavfall", "matavfall", "Klikk her for å pinge Matavfall Task"),
                                    SelectMenuOption.create("Rydde (Teams)", "rydde", "Klikk her for å pinge Rydde (Teams)")))))
                            .setFlags(MessageFlag.EPHEMERAL)
                            .respond();
                } else if(cmd.equals("pingstatus")) {
                    sendStatus(slashInt);
                }
            } else {
                slashInt.createImmediateResponder()
                    .setContent("Trokkje du bør bruke denne her commanden asså")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond();
            }
        });

        api.addSelectMenuChooseListener(event -> {
            List<SelectMenuOption> tasks = event.getSelectMenuInteraction().getChosenOptions();
            tasks.forEach(taskOption -> {
                String task = taskOption.getValue();
                long person = getCurrentPerson(task);
                String personName = getName(person);
                if (!personName.isEmpty()) {
                    if (!tasksPinged.containsKey(task)) {
                        String secName = pingTask(task);
                        event.getInteraction().createImmediateResponder().setContent("Pinget " + personName + (!secName.isEmpty() ? " og " + secName : "") + " om " + task)
                                .setFlags(MessageFlag.EPHEMERAL).respond();
                    } else {
                        event.getInteraction().createImmediateResponder().setContent("Har allerede pinget " + task).setFlags(MessageFlag.EPHEMERAL).respond();
                    }
                }
            });
        });

        api.addButtonClickListener(event -> {
            ButtonInteraction messageComponentInteraction = event.getButtonInteraction();
            String task = messageComponentInteraction.getCustomId();
            long person = event.getInteraction().getUser().getId();
            if ((!task.equalsIgnoreCase("rydde") && person == getCurrentPerson(task))
                    || (task.equalsIgnoreCase("rydde") && tasksPinged.containsKey(task) && (person == getCurrentPerson(task) || person == getSecondPerson(task)))) {
                messageComponentInteraction.getMessage().delete();
                setNextPerson(task, true);
                event.getInteraction().createImmediateResponder()
                    .setContent(task + " listen har blitt oppdatert")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond();
            } else {
                messageComponentInteraction.getMessage().delete();
            }
        });
    }
}
