package net.atos.entng.calendar;

import org.entcore.common.mongodb.MongoDbResult;
import org.entcore.common.service.impl.MongoDbRepositoryEvents;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;

import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.mongodb.MongoUpdateBuilder;
import fr.wseduc.webutils.Either;

public class CalendarRepositoryEvents extends MongoDbRepositoryEvents {

    @Override
    public void exportResources(String exportId, String userId, JsonArray groups, String exportPath, String locale, String host, Handler<Boolean> handler) {
        // TODO Auto-generated method stub
        log.warn("[CalendarRepositoryEvents] exportResources is not implemented");
    }

    @Override
    public void deleteGroups(JsonArray groups) {
        if (groups == null || groups.size() == 0) {
            log.warn("[CalendarRepositoryEvents][deleteGroups] JsonArray groups is null or empty");
            return;
        }

        final String[] groupIds = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            JsonObject j = groups.get(i);
            groupIds[i] = j.getString("group");
        }

        final JsonObject matcher = MongoQueryBuilder.build(QueryBuilder.start("shared.groupId").in(groupIds));

        MongoUpdateBuilder modifier = new MongoUpdateBuilder();
        modifier.pull("shared", MongoQueryBuilder.build(QueryBuilder.start("groupId").in(groupIds)));
        // remove all the shares with groups
        mongo.update(Calendar.CALENDAR_COLLECTION, matcher, modifier.build(), false, true, MongoDbResult.validActionResultHandler(new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    log.info("[CalendarRepositoryEvents][deleteGroups] All groups shares are removed");
                } else {
                    log.error("[CalendarRepositoryEvents][deleteGroups] Error removing groups shares. Message : " + event.left().getValue());
                }
            }
        }));
    }

    @Override
    public void deleteUsers(JsonArray users) {
        // TODO : make the user anonymous
        if (users == null || users.size() == 0) {
            log.warn("[CalendarRepositoryEvents][deleteUsers] JsonArray users is null or empty");
            return;
        }

        final String[] usersIds = new String[users.size()];
        for (int i = 0; i < users.size(); i++) {
            JsonObject j = users.get(i);
            usersIds[i] = j.getString("id");
        }
        /*
         * Clean the database : - First, remove shares of all the categories shared with (usersIds) - then, get the
         * categories identifiers that have no user and no manger, - delete all these categories, - delete all the
         * subjects that do not belong to a category - finally, tag all users as deleted in their own categories
         */

        this.removeSharesCalendars(usersIds);
    }

    /**
     * Remove the shares of categories with a list of users if OK, Call prepareCleanCategories()
     * @param usersIds users identifiers
     */
    private void removeSharesCalendars(final String[] usersIds) {
        final JsonObject criteria = MongoQueryBuilder.build(QueryBuilder.start("shared.userId").in(usersIds));
        MongoUpdateBuilder modifier = new MongoUpdateBuilder();
        modifier.pull("shared", MongoQueryBuilder.build(QueryBuilder.start("userId").in(usersIds)));

        // Remove Categories shares with these users
        mongo.update(Calendar.CALENDAR_COLLECTION, criteria, modifier.build(), false, true, MongoDbResult.validActionResultHandler(new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    log.info("[CalendarRepositoryEvents][removeSharesCalendars] All calendars shares with users are removed");
                    prepareCleanCalendars(usersIds);
                } else {
                    log.error("[CalendarRepositoryEvents][removeSharesCalendars] Error removing calendars shares with users. Message : " + event.left().getValue());
                }
            }
        }));
    }

    /**
     * Prepare a list of categories identifiers if OK, Call cleanCategories()
     * @param usersIds users identifiers
     */
    private void prepareCleanCalendars(final String[] usersIds) {
        DBObject deletedUsers = new BasicDBObject();
        // users currently deleted
        deletedUsers.put("owner.userId", new BasicDBObject("$in", usersIds));
        // users who have already been deleted
        DBObject ownerIsDeleted = new BasicDBObject("owner.deleted", true);
        // no manager found
        JsonObject matcher = MongoQueryBuilder.build(QueryBuilder.start("shared." + Calendar.MANAGE_RIGHT_ACTION).notEquals(true).or(deletedUsers, ownerIsDeleted));
        // return only calendar identifiers
        JsonObject projection = new JsonObject().putNumber("_id", 1);

        mongo.find(Calendar.CALENDAR_COLLECTION, matcher, null, projection, MongoDbResult.validResultsHandler(new Handler<Either<String, JsonArray>>() {
            @Override
            public void handle(Either<String, JsonArray> event) {
                if (event.isRight()) {
                    JsonArray calendars = event.right().getValue();
                    if (calendars == null || calendars.size() == 0) {
                        log.info("[CalendarRepositoryEvents][prepareCleanCalendars] No calendars to delete");
                        return;
                    }
                    final String[] calendarIds = new String[calendars.size()];
                    for (int i = 0; i < calendars.size(); i++) {
                        JsonObject j = calendars.get(i);
                        calendarIds[i] = j.getString("_id");
                    }
                    cleanCalendars(usersIds, calendarIds);
                } else {
                    log.error("[CalendarRepositoryEvents][prepareCleanCalendars] Error retreving the calendars created by users. Message : " + event.left().getValue());
                }
            }
        }));
    }

    /**
     * Delete calendars by identifier if OK, call cleanEvents() and tagUsersAsDeleted()
     * @param usersIds users identifiers, used for tagUsersAsDeleted()
     * @param calendarIds calendars identifiers
     */
    private void cleanCalendars(final String[] usersIds, final String[] calendarIds) {
        JsonObject matcher = MongoQueryBuilder.build(QueryBuilder.start("_id").in(calendarIds));

        mongo.delete(Calendar.CALENDAR_COLLECTION, matcher, MongoDbResult.validActionResultHandler(new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    log.info("[CalendarRepositoryEvents][cleanCalendars] The calendars created by users are deleted");
                    cleanEvents(calendarIds);
                    tagUsersAsDeleted(usersIds);
                } else {
                    log.error("[CalendarRepositoryEvents][cleanCalendars] Error deleting the calendars created by users. Message : " + event.left().getValue());
                }
            }
        }));
    }

    /**
     * Delete events by calendar identifier
     * @param calendarIds calendars identifiers
     */
    private void cleanEvents(final String[] calendarIds) {
        JsonObject matcher = MongoQueryBuilder.build(QueryBuilder.start("calendar").in(calendarIds));

        mongo.delete(Calendar.CALENDAR_EVENT_COLLECTION, matcher, MongoDbResult.validActionResultHandler(new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    log.info("[CalendarRepositoryEvents][cleanEvents] The events created by users are deleted");
                } else {
                    log.error("[CalendarRepositoryEvents][cleanEvents] Error deleting the events created by users. Message : " + event.left().getValue());
                }
            }
        }));
    }

    /**
     * Tag as deleted a list of users in their own calendars
     * @param userIds users identifiers
     */
    private void tagUsersAsDeleted(final String[] usersIds) {
        final JsonObject criteria = MongoQueryBuilder.build(QueryBuilder.start("owner.userId").in(usersIds));
        MongoUpdateBuilder modifier = new MongoUpdateBuilder();
        modifier.set("owner.deleted", true);

        mongo.update(Calendar.CALENDAR_COLLECTION, criteria, modifier.build(), false, true, MongoDbResult.validActionResultHandler(new Handler<Either<String, JsonObject>>() {
            @Override
            public void handle(Either<String, JsonObject> event) {
                if (event.isRight()) {
                    log.info("[CalendarRepositoryEvents][tagUsersAsDeleted] users are tagged as deleted in their own calendars");
                } else {
                    log.error("[CalendarRepositoryEvents][tagUsersAsDeleted] Error tagging as deleted users. Message : " + event.left().getValue());
                }
            }
        }));
    }

}