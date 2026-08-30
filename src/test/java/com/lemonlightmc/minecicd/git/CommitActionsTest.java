package com.lemonlightmc.minecicd.git;

import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.git.CommitActions.ParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommitActionsTest {

    @Test
    void parsesCommitMessageActions() {
        List<Action> actions = CommitActions.parseCommitMessage(
                "feat: deploy\nCICD restart\nCICD run say hi\nCICD script deploy\n");
        assertEquals(3, actions.size());
        assertEquals(ActionType.RESTART, actions.get(0).type());
        assertEquals(ActionType.COMMAND, actions.get(1).type());
        assertEquals("say hi", actions.get(1).argument());
        assertEquals(ActionType.SCRIPT, actions.get(2).type());
        assertEquals("deploy", actions.get(2).argument());
    }

    @Test
    void parsesControlItems() {
        assertEquals(ActionType.PULL, CommitActions.parseControlItem("pull").type());
        assertEquals(ActionType.PUSH, CommitActions.parseControlItem("push").type());
        Action pushMsg = CommitActions.parseControlItem("push:deploy v2");
        assertEquals(ActionType.PUSH, pushMsg.type());
        assertEquals("deploy v2", pushMsg.argument());
        Action reload = CommitActions.parseControlItem("reload:essentials");
        assertEquals(ActionType.RELOAD_PLUGIN, reload.type());
        assertEquals("essentials", reload.argument());
    }

    @Test
    void rejectsUnknownActions() {
        assertThrows(ParseException.class, () -> CommitActions.parseControlItem("bogus"));
        assertThrows(ParseException.class, () -> CommitActions.parseControlItem("push:"));
    }

    @Test
    void emptyMessageYieldsNoActions() {
        assertEquals(0, CommitActions.parseCommitMessage("just a normal commit").size());
        assertEquals(0, CommitActions.parseCommitMessage((String) null).size());
        assertEquals(0, CommitActions.parseCommitMessage("").size());
    }
}