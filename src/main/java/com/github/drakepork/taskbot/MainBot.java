package com.github.drakepork.taskbot;

import org.apache.commons.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MainBot {
    private static DatabaseHook db;
    private static DiscordApi api;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Logger logger = LogManager.getLogger(MainBot.class);
    private static final List<Task> tasks = new ArrayList<>();
    private static final List<Person> persons = new ArrayList<>();
    public record Person(String name, long id) {}
    private static void updateOrder(Task task) {
        List<Person> order = task.getOrder();
        if(task.isTeams()) {
            order.removeFirst();
            order.removeFirst();
            if(order.size() == 1) {
                Person person = order.getFirst();
                List<Person> randOrder = new ArrayList<>(persons);
                randOrder.remove(person);
                Collections.shuffle(randOrder);
                order.addAll(randOrder);
            }
        } else {
            order.addLast(order.removeFirst());
        }
        String peopleIds = order.stream()
                .map(Person::id).map(String::valueOf)
                .collect(Collectors.joining(","));
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement("UPDATE tasks SET order_list = ? WHERE task_name = ?")) {
            ps.setString(1, peopleIds);
            ps.setString(2, task.getName());
            ps.executeUpdate();
            task.setOrder(order);
            logger.info("Successfully updated order list for " + task.getName());
        } catch (SQLException e) {
            logger.error("Couldn't update order list in database!", e);
        }
    }
    private static void setNextPing(Task task) {
        long interval = task.getInterval();
        if(interval == 0) return;
        ZonedDateTime dateTime = ZonedDateTime.of(LocalDate.now(), LocalTime.of(20, 0), ZoneId.systemDefault());
        long nextPing = dateTime.toInstant().toEpochMilli() + interval;
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET next_ping = ? WHERE task_name = ?")) {
            ps.setLong(1, nextPing);
            ps.setString(2, task.getName());
            ps.executeUpdate();
            task.setNextPing(nextPing);
            logger.info("Successfully set next ping for " + task.getName());
        } catch (SQLException e) {
            logger.error("Couldn't set next ping in database!", e);
        }
    }
    private static boolean canUseCommands(User person) {
        return persons.stream().anyMatch(p -> p.id() == person.getId());
    }
    public static void startScheduledTasks() {
        final Runnable taskRunner = new Runnable() {
            public void run() {
                autoPingTasks();
                pingedTasks();
            }

            private void autoPingTasks() {
                LocalDateTime now = LocalDateTime.now();
                int hour = now.getHour();
                boolean general = hour == 10;
                boolean dunkene = hour == 17;

                if (!general && !dunkene) return;
                List<Task> autoTasks = tasks.stream().filter(task -> task.getNextPing() != 0 &&
                        ((general && task.isType("general")) || (dunkene && task.isType("dunk")))).toList();
                for (Task task : autoTasks) {
                    LocalDateTime pingDay = LocalDateTime.ofInstant(Instant.ofEpochMilli(task.getNextPing()), ZoneId.systemDefault());
                    if (pingDay.isBefore(now)) {
                        setNextPing(task);
                        continue;
                    }
                    if (task.isPinged() || now.getDayOfYear() != pingDay.getDayOfYear()) continue;
                    pingTask(task);
                }
            }

            private void pingedTasks() {
                tasks.stream().filter(Task::isPinged).forEach(task -> {
                    long pingTime = TimeUnit.HOURS.toMillis(8) + task.getLastPing();
                    boolean isDunker = task.isType("dunk");
                    if (isDunker) pingTime = TimeUnit.MINUTES.toMillis(15) + task.getLastPing();

                    if (pingTime > System.currentTimeMillis()) return;
                    task.getMessages().forEach(Message::delete);

                    if (isDunker) {
                        ZonedDateTime firstPinged = Instant.ofEpochMilli(task.getFirstPinged())
                                .atZone(ZoneId.systemDefault());
                        ZonedDateTime now = ZonedDateTime.now();

                        long hoursSinceFirstPinged = ChronoUnit.HOURS.between(firstPinged, now);
                        if (hoursSinceFirstPinged >= 18) {
                            task.setPinged(false);
                            return;
                        }
                    }
                    sendTaskMsg(task);
                    task.setLastPing(System.currentTimeMillis());
                });
            }
        };

        scheduler.scheduleAtFixedRate(taskRunner, 0, 5, TimeUnit.MINUTES);
    }
    private static void sendTaskMsg(Task task) {
        logger.info("Sending " + task.getName() + " task message");
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("-= " + task.getName().replace("_", " ").toUpperCase() + " TIME =-");

        MessageBuilder msg = new MessageBuilder();
        msg.addComponents(ActionRow.of(Button.success(task.getName(), "Trykk når du er ferdig")));

        List<Message> messages = new ArrayList<>();
        AtomicInteger i = new AtomicInteger(1);
        task.getPingedPersons().forEach(person -> {
            String desc = task.getDescription();
            if(task.isTeams()) {
                desc += "\n\nDIN LAG-KAMERAT ER: " + task.getPingedPersons().get(i.get()).name();
                i.getAndDecrement();
            }
            embed.setDescription(desc);
            msg.setEmbed(embed);
            User user = api.getUserById(person.id()).exceptionally(e -> {logger.error("Couldn't get user!", e); return null;}).join();
            if(user == null) {
                logger.warn("Couldn't find user with id " + person.id());
                return;
            }
            Message message = msg.send(user).join();
            if(message != null) {
                messages.add(message);
                logger.info("Sent message to " + person.name());
            } else {
                logger.error("Couldn't send message to " + person.name());
                persons.stream().filter(p -> p.name().equalsIgnoreCase("andreas")).findFirst().ifPresent(p -> {
                    MessageBuilder error = new MessageBuilder();
                    error.setContent("Klarte ikke å sende melding til " + person.name() + " for " + task.getName() + " task");
                    error.send(user);
                });
                task.setPinged(false);
            }
        });
        task.setMessages(messages);
        logger.info("Successfully sent " + task.getName() + " task message");
    }
    public static void pingTask(Task task) {
        logger.info("Pinging " + task.getName() + " task");
        long pingTime = System.currentTimeMillis();

        List<Person> peopleToPing = task.getOrder().subList(0, task.isTeams() ? 2 : 1);
        task.setPinged(true);
        task.setLastPing(pingTime);
        task.setFirstPinged(pingTime);
        task.setPingedPersons(peopleToPing);
        setNextPing(task);
        sendTaskMsg(task);
    }
    private static void sendStatus(SlashCommandInteraction slashInt) {
        StringBuilder desc = new StringBuilder();

        tasks.forEach(task -> {
            String peopleNames = task.isPinged() ? task.getPingedPersons().stream().map(Person::name).collect(Collectors.joining(", "))
                    : task.isTeams() ? (task.getOrder().getFirst().name() + ", " + task.getOrder().get(1).name()) : task.getOrder().getFirst().name();
            desc.append("**").append(WordUtils.capitalize(task.getName().replace("_", " "))).append("**\n")
                    .append("- Er Pinget: ").append(task.isPinged() ? "Ja" : "Nei").append("\n")
                    .append("- ").append(task.isPinged() ? "Pinget" : "Neste").append(" Person: ").append(peopleNames).append("\n");
            if(task.isPinged()) {
                long lastPinged = task.getLastPing();
                long nextPing = lastPinged + (task.isType("dunk") ? TimeUnit.MINUTES.toMillis(15) : TimeUnit.HOURS.toMillis(8));

                LocalDateTime currentTime = LocalDateTime.now();
                LocalDateTime nextPingTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nextPing), ZoneId.systemDefault());
                LocalDateTime lastPingTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastPinged), ZoneId.systemDefault());
                LocalDateTime firstPingTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(task.getFirstPinged()), ZoneId.systemDefault());

                String timeTillNextPing = formatDuration(currentTime, nextPingTime);
                String timeSinceLastPing = formatDuration(lastPingTime, currentTime);
                String timeSinceFirstPing = formatDuration(firstPingTime, currentTime);

                desc.append("- Sist Pinget: ").append(timeSinceLastPing).append(" siden").append("\n")
                        .append("- Neste Ping: ").append("Om ").append(timeTillNextPing).append("\n")
                        .append("- Første Ping: ").append(timeSinceFirstPing).append(" siden").append("\n");
            }
            long autoPing = task.getNextPing();
            if(autoPing != 0) {
                LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(autoPing), ZoneId.systemDefault());
                int day = dateTime.getDayOfMonth();
                String month = dateTime.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("no-NO"));
                desc.append("- Neste Auto Ping: ").append(day).append(" ").append(month).append("\n");
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
    private static String formatDuration(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        if (hours > 0) {
            return hours + " time" + (hours != 1 ? "r, " : ", ") + minutes + " minutt" + (minutes != 1 ? "er" : "");
        } else {
            return minutes + " minutt" + (minutes != 1 ? "er" : "");
        }
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
    private static Task getTask(String taskName) {
        return tasks.stream().filter(task -> task.getName().equalsIgnoreCase(taskName)).findFirst().orElse(null);
    }
    private static void initialSetup() {
        Properties config = new Properties();
        String externalConfigPath = "./config.properties";

        File configFile = new File(externalConfigPath);
        if (!configFile.exists()) {
            generateConfig(configFile);
        }

        try (FileInputStream fileStream = new FileInputStream(externalConfigPath)) {
            config.load(fileStream);
            logger.info("Successfully loaded the config file!");
        } catch (IOException e) {
            logger.error("Config file doesn't exist or couldn't be loaded!");
        }
        String token = config.getProperty("discord.token");
        if(token == null || token.isEmpty()) {
            logger.error("Discord token not set!");
        }
        api = new DiscordApiBuilder()
                .setToken(token)
                .login().join();
        if(api == null) {
            logger.error("Failed to connect to Discord!");
        }

        db = new DatabaseHook(config);
        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM people")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                persons.add(new Person(rs.getString(2), rs.getLong(1)));
            }
            logger.info("Successfully got people from database!");
        } catch (SQLException e) {
            logger.error("Couldn't get people from database!", e);
        }

        try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM tasks")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String taskName = rs.getString(1);
                String taskDesc = rs.getString(2);
                String taskType = rs.getString(3);
                List<Person> order = new ArrayList<>();
                List<String> orderList = Arrays.asList(rs.getString(4).split(","));
                orderList.forEach(person -> order.add(persons.stream().filter(p -> p.id() == Long.parseLong(person)).findFirst().orElse(null)));
                boolean isTeams = rs.getBoolean(5);
                long nextPing = rs.getLong(6);
                long interval = rs.getLong(7);
                tasks.add(new Task(taskName, taskDesc, taskType, order, isTeams, nextPing, interval));
            }
            logger.info("Successfully got tasks from database!");
        } catch (SQLException e) {
            logger.error("Couldn't get tasks from database!", e);
        }

        SlashCommand.with("pingtask", "pinge tasks")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createGlobal(api)
                .join();
        SlashCommand.with("pingstatus", "Sjekke status på tasks")
                .setDefaultEnabledForPermissions(PermissionType.ADMINISTRATOR)
                .createGlobal(api)
                .join();


        FallbackLoggerConfiguration.setDebug(true);
        FallbackLoggerConfiguration.setTrace(true);
        startScheduledTasks();
        logger.info("Successfully started the bot!");
    }
    private static void generateConfig(File configFile) {
        Properties defaultConfig = getDefaultProperties();
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            defaultConfig.store(fos, "Default config values");
            logger.info("Successfully created the config file!");
        } catch (IOException e) {
            logger.error("Error creating the config file!", e);
        }
    }
    private static void createCommands() {
        api.addSlashCommandCreateListener(event -> {
            SlashCommandInteraction slashInt = event.getSlashCommandInteraction();
            User commandUser = slashInt.getUser();
            if (canUseCommands(commandUser)) {
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
                                                    SelectMenuOption.create("Rydde (Teams)", "rydde", "Klikk her for å pinge Rydde (Teams)"),
                                                    SelectMenuOption.create("Kjøkken Rydding", "kjøkken_rydding", "Klikk her for å pinge Kjøkken Rydding")))))
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
            List<SelectMenuOption> options = event.getSelectMenuInteraction().getChosenOptions();
            options.forEach(taskOption -> {
                Task task = getTask(taskOption.getValue());

                if(task == null) return;
                List<Person> people = task.getOrder().subList(0, task.isTeams() ? 2 : 1);
                String peopleNames = people.stream()
                        .map(Person::name)
                        .collect(Collectors.joining(", "));
                if(task.isPinged()) {
                    event.getInteraction().createImmediateResponder().setContent("Har allerede pinget " + peopleNames + " om " + task.getName()).setFlags(MessageFlag.EPHEMERAL).respond();
                    return;
                }

                pingTask(task);
                event.getInteraction().createImmediateResponder().setContent("Pinget " + peopleNames + " om " + task.getName()).setFlags(MessageFlag.EPHEMERAL).respond();
            });
        });

        api.addButtonClickListener(event -> {
            ButtonInteraction interaction = event.getButtonInteraction();
            String taskId = interaction.getCustomId();
            Task task = getTask(taskId);
            if(task == null) {
                interaction.createImmediateResponder()
                        .setContent("Fant ikke tasken")
                        .setFlags(MessageFlag.EPHEMERAL)
                        .respond();
                logger.warn("Couldn't find task with id " + taskId);
                return;
            }

            Person person = task.getPingedPersons().stream().filter(p -> p.id() == interaction.getUser().getId()).findFirst().orElse(null);

            if(!task.isPinged() || person == null) {
                interaction.getMessage().delete();
                logger.info("User " + interaction.getUser().getName() + " removed old " + task.getName() + " task message");
                return;
            }

            task.getMessages().forEach(Message::delete);
            task.setPinged(false);
            event.getInteraction().createImmediateResponder()
                    .setContent(task.getName() + " listen har blitt oppdatert")
                    .setFlags(MessageFlag.EPHEMERAL)
                    .respond();
            updateOrder(task);
            logger.info(person.name() + " har fullført " + task.getName() + " task");
        });
    }
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down the bot");
            scheduler.shutdown();
            api.disconnect();
        }));
    }
    public static void main(String[] args) {
        initialSetup();
        createCommands();
    }
}
