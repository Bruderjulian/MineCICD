package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.git.CommitActions.Action;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ControlRequest {

    private final String requestId;
    private final String branch;
    private final List<Action> actions;

    public ControlRequest(String requestId, String branch, List<Action> actions) {
        this.requestId = requestId;
        this.branch = branch;
        this.actions = actions;
    }

    public String requestId() {
        return requestId;
    }

    public String branch() {
        return branch;
    }

    public List<Action> actions() {
        return actions;
    }

    public static ControlRequest parse(String body) {
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            throw new ParseException("Invalid JSON body");
        }
        if (!json.has("requestId")) {
            throw new ParseException("Missing requestId");
        }
        String requestId = json.getString("requestId");
        String branch = json.has("branch") && !json.isNull("branch") ? json.getString("branch") : null;
        if (!json.has("actions")) {
            throw new ParseException("Missing actions");
        }
        JSONArray array = json.getJSONArray("actions");
        List<Action> actions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof String s) {
                actions.add(com.lemonlightmc.minecicd.git.CommitActions.parseControlItem(s));
            } else {
                throw new ParseException("Action at index " + i + " must be a string");
            }
        }
        if (actions.isEmpty()) {
            throw new ParseException("actions must not be empty");
        }
        return new ControlRequest(requestId, branch, actions);
    }

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }
}