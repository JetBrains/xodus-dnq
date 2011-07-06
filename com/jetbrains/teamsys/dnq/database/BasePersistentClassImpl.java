package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.BasePersistentClass;
import com.jetbrains.teamsys.database.Entity;
import com.jetbrains.teamsys.database.EntityIterable;

import java.util.ArrayList;
import java.util.List;


/**
 *
 */
public abstract class BasePersistentClassImpl extends BasePersistentClass{

    protected List<String> createPerInstanceErrorMessage(MessageBuildser messageBuilder, EntityIterable linkedEntities){
        List<String> res = new ArrayList<String>();
        for (Entity entity : linkedEntities.take(MAX_LINKED_ENTITIES_TO_SHOW)) {
            res.add(messageBuilder.build(null, entity));
        }
        long leftMore = linkedEntities.size() - MAX_LINKED_ENTITIES_TO_SHOW;
        if(leftMore > 0) res.add("And " + leftMore + " more...");
        return res;
    }

    protected String createPerTypeErrorMessage(MessageBuildser messageBuilder, EntityIterable linkedEntities){
        return messageBuilder.build(linkedEntities, null);
    }

}
