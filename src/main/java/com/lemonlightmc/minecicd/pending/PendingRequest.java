package com.lemonlightmc.minecicd.pending;

import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PendingRequest {

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INTERRUPTED
    }

    private final String requestId;
    private final List<Action> actions;
    private final int total;
    private int index;
    private Status status;
    private String error;
    private final String branch;

    public PendingRequest(String requestId, List<Action> actions, String branch) {
        this(requestId, actions, 0, Status.RUNNING, null, branch);
    }

    private PendingRequest(String requestId, List<Action> actions, int index, Status status, String error, String branch) {
        this.requestId = requestId;
        this.actions = new ArrayList<>(actions);
        this.total = actions.size();
        this.index = index;
        this.status = status;
        this.error = error;
        this.branch = branch;
    }

    public String requestId() {
        return requestId;
    }

    public List<Action> actions() {
        return actions;
    }

    public int total() {
        return total;
    }

    public int index() {
        return index;
    }

    public Status status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String branch() {
        return branch;
    }

    public boolean hasRemaining() {
        return index < total;
    }

    public Action current() {
        return actions.get(index);
    }

    public void advance() {
        index++;
    }

    public void completed() {
        index = total;
        status = Status.COMPLETED;
    }

    public void failed(String message) {
        status = Status.FAILED;
        error = message;
    }

    public void interrupted(String message) {
        status = Status.INTERRUPTED;
        error = message;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("requestId", requestId);
        json.put("branch", branch == null ? "" : branch);
        JSONArray array = new JSONArray();
        for (Action action : actions) {
            JSONObject a = new JSONObject();
            a.put("type", action.type().name());
            a.put("argument", action.argument() == null ? "" : action.argument());
            array.put(a);
        }
        json.put("actions", array);
        json.put("index", index);
        json.put("status", status.name());
        json.put("error", error == null ? "" : error);
        return json;
    }

    public static PendingRequest fromJson(JSONObject json) {
        String requestId = json.getString("requestId");
        String branch = json.optString("branch", "");
        JSONArray array = json.getJSONArray("actions");
        List<Action> actions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject a = array.getJSONObject(i);
            String type = a.getString("type");
            String argument = a.optString("argument", "");
            actions.add(new Action(ActionType.valueOf(type), argument.isEmpty() ? null : argument));
        }
        int index = json.optInt("index", 0);
        String status = json.optString("status", Status.RUNNING.name());
        String error = json.optString("error", "");
        return new PendingRequest(requestId, actions, index,
                error.isEmpty() ? Status.valueOf(status) : Status.valueOf(status), error, branch);
    }
}