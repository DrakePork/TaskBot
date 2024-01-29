package com.github.drakepork.taskbot;

import org.javacord.api.entity.user.User;

public class Person {
    private final String name;
    private final User user;
    private final long id;
    public Person(String name, User user, long id) {
        this.name = name;
        this.user = user;
        this.id = id;
    }
    public String getName() {
        return name;
    }
    public User getUser() {
        return user;
    }
    public long getId() {
        return id;
    }
}
