package com.github.drakepork.taskbot;

import org.javacord.api.entity.message.Message;

import java.util.ArrayList;
import java.util.List;

public class Task {
    private final String name;
    private final String description;
    private final String type;
    private long nextPing;
    private final long interval;
    private List<MainBot.Person> order;
    private final boolean isTeams;
    private boolean isPinged = false;
    private List<MainBot.Person> pingedPersons = new ArrayList<>();
    private List<Message> messages = new ArrayList<>();
    private long lastPing = 0;
    private long firstPinged = 0;
    public Task(String name, String description, String type, List<MainBot.Person> order, boolean isTeams, long nextPing, long interval) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.order = order;
        this.isTeams = isTeams;
        this.nextPing = nextPing;
        this.interval = interval;
    }
    public String getName() {
        return name;
    }
    public String getDescription() {
        return description;
    }
    public String getType() {
        return type;
    }
    public boolean isType(String type) {
        return this.type.equalsIgnoreCase(type);
    }
    public List<MainBot.Person> getOrder() {
        return order;
    }
    public void setOrder(List<MainBot.Person> order) {
        this.order = order;
    }
    public boolean isTeams() {
        return isTeams;
    }

    public long getNextPing() {
        return nextPing;
    }
    public void setNextPing(long nextPing) {
        this.nextPing = nextPing;
    }
    public long getInterval() {
        return interval;
    }
    public boolean isPinged() {
        return isPinged;
    }
    public void setPinged(boolean pinged) {
        isPinged = pinged;
    }
    public List<MainBot.Person> getPingedPersons() {
        return pingedPersons;
    }
    public void setPingedPersons(List<MainBot.Person> pingedPersons) {
        this.pingedPersons = pingedPersons;
    }
    public List<Message> getMessages() {
        return messages;
    }
    public void setMessages(List<Message> messageIds) {
        this.messages = messageIds;
    }
    public long getLastPing() {
        return lastPing;
    }
    public void setLastPing(long lastPing) {
        this.lastPing = lastPing;
    }
    public long getFirstPinged() {
        return firstPinged;
    }
    public void setFirstPinged(long firstPinged) {
        this.firstPinged = firstPinged;
    }
}
