package net.atos.entng.calendar.ical;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;

import net.atos.entng.calendar.exception.CalendarException;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Date;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

/**
 * ICal worker to handle ICS parsing and generation
 * @author Atos
 *
 */
public class ICalHandler extends Verticle implements Handler<Message<JsonObject>> {

    public static final String ICAL_HANDLER_ADDRESS = "ical.handler";


    /**
     * Actions handled by worker
     */
    public static final String ACTION_PUT = "put";
    public static final String ACTION_GET = "get";
    
    /**
     * Simple Date formatter in moment.js format
     */
    private static final SimpleDateFormat MOMENT_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Override
    public void start() {
        super.start();
        vertx.eventBus().registerHandler(ICAL_HANDLER_ADDRESS, this);
    }

    @Override
    public void handle(Message<JsonObject> message) {
        String action = message.body().getString("action", "");
        switch (action) {
            case "get":
                jsonEventsToIcsContent(message);
                break;
            case "put":
                icsContentToJsonEvents(message);
                break;
        }
        
    }
    
    /**
     * Get message containing a JsonArray filled with events and reply by ICS generated data
     * @param message Contains JsonArray filled with events
     */
    private void jsonEventsToIcsContent(Message<JsonObject> message) {
        JsonObject results = new JsonObject();
        JsonObject body = message.body();
        Calendar calendar = new Calendar();
        initCalendarProperties(calendar);
        JsonArray calendarEvents = body.getArray("events");
        for (Object calendarEvent : calendarEvents) {
            JsonObject ce = (JsonObject) calendarEvent;
            String startMoment = ce.getString("startMoment");
            String endMoment = ce.getString("endMoment");
            String title = ce.getString("title");
            String icsUid = ce.getString("icsUid");
            boolean allDay = ce.containsField("allday") && ce.getBoolean("allday");
            try {
                java.util.Date startDate = MOMENT_FORMAT.parse(startMoment);
                java.util.Date endDate = MOMENT_FORMAT.parse(endMoment);
                if (allDay) {
                    addAllDayEvent(calendar, startDate, title, icsUid);
                } else {
                    addEvent(calendar, startDate, endDate, title, icsUid);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        results.putString("ics", calendar.toString());
        results.putNumber("status", 200);
        message.reply(results);
    }
    
    /**
     * Get message containg ICS data and reply by a JsonArray containg all calendar events in Json format
     * @param message Contains ICS data 
     */
    private void icsContentToJsonEvents(Message<JsonObject> message) {
        String icsContent = message.body().getString("ics");
        CalendarBuilder calendarBuilder = new CalendarBuilder();
        InputStream inputStream = new ByteArrayInputStream(icsContent.getBytes());

        try {
            Calendar calendar = calendarBuilder.build(inputStream);
            JsonArray events = new JsonArray();
            ComponentList components = calendar.getComponents();
            for (Object component : components) {
                if (component instanceof VEvent) {
                    JsonObject jsonEvent = new JsonObject();
                    VEvent event = (VEvent) component;
                    setEventDates(event, jsonEvent);
                    setEventProperties(event, jsonEvent);
                    events.add(jsonEvent);
                }
            }
            JsonObject results = new JsonObject();
            results.putArray("events", events);
            message.reply(results);
        } catch (IOException | ParserException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } 
    }
    
    /**
     * Set ical4j event properties to JsonObject
     * @param event ical4j filled event
     * @param jsonEvent JsonObject event to fill
     */
    private void setEventProperties(VEvent event, JsonObject jsonEvent) {
        
        String title = event.getSummary().getValue();
        String location = event.getLocation() != null ? event.getLocation().getValue() : "";
        String description = event.getDescription() != null ? event.getDescription().getValue() : "";
        String uid = event.getUid() != null ? event.getUid().getValue() : "";
        
        if (!title.isEmpty()) {
            jsonEvent.putString("title", title);
        }
        if (!location.isEmpty()) {
            jsonEvent.putString("location", location);
        }
        if (!description.isEmpty()) {
            jsonEvent.putString("description", description);
        }
        if (!uid.isEmpty()) {
            jsonEvent.putString("icsUid", uid);
        }
    }
    
    
    /**
     * Set ical4j event dates to JsonObject with moment.js formatting
     * @param event ical4j filled event
     * @param jsonEvent JsonObject event to fill
     */
    private void setEventDates(VEvent event, JsonObject jsonEvent) {
        // get DTSTART;VALUE parameter
        String dtStartValue = event.getStartDate().getParameter(Parameter.VALUE) != null ? event.getStartDate().getParameter(Parameter.VALUE).getValue() : "";
        // check if DTSTART;VALUE=DATE 
        boolean allDay = dtStartValue.equals("DATE");
        Date startDate = event.getStartDate().getDate();
        Date endDate = event.getEndDate().getDate();
        String startMoment = MOMENT_FORMAT.format(startDate);
        String endMoment = MOMENT_FORMAT.format(endDate);
        
        // If allDay, set Hours to 0 instead of 1
        if (allDay) {
            java.util.Calendar calendar = new GregorianCalendar();
            // Start Date
            calendar.setTime(startDate);
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
            startMoment = MOMENT_FORMAT.format(calendar.getTime());
            // End Date
            calendar.setTime(endDate);
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
            endMoment = MOMENT_FORMAT.format(calendar.getTime());
            jsonEvent.putBoolean("allday", allDay);
        }
        // Put dates to jsonEvent
        jsonEvent.putString("startMoment", startMoment);
        jsonEvent.putString("endMoment", endMoment);
    }

    /**
     * Init ical4j calendar properties (ProdId, Version, CalScale)
     * @param calendar Ical4j calendar
     */
    private void initCalendarProperties(Calendar calendar) {
        calendar.getProperties().add(new ProdId("-//OpenENT Calendar 1.0//EN"));
        calendar.getProperties().add(Version.VERSION_2_0);
        calendar.getProperties().add(CalScale.GREGORIAN);
    }

    /**
     * Add an all day event to ical4j calendar
     * @param calendar Ical4j calendar
     * @param date Event date
     * @param title Event title
     * @throws CalendarException
     */
    private void addAllDayEvent(Calendar calendar, java.util.Date date, String title, String icsUid) throws CalendarException {
        VEvent event = new VEvent(new Date(date.getTime()), title);
        try {
            Uid uid = new Uid();
            uid.setValue(icsUid);
            event.getProperties().add(uid);
            calendar.getComponents().add(event);
        } catch (Exception e) {
            throw new CalendarException(e);
        }
    }

    /**
     * Add an event to ical4j calendar
     * @param calendar Ical4j calendar
     * @param startDate Event start date
     * @param endDate Event end date
     * @param title Event title
     * @throws CalendarException
     */
    private void addEvent(Calendar calendar, java.util.Date startDate, java.util.Date endDate, String title, String icsUid) throws CalendarException {
        DateTime startDateTime = new DateTime(startDate);
        DateTime endDateTime = new DateTime(endDate);
        VEvent event = new VEvent(startDateTime, endDateTime, title);

        try {
            Uid uid = new Uid();
            uid.setValue(icsUid);
            event.getProperties().add(uid);
            calendar.getComponents().add(event);
        } catch (Exception e) {
            throw new CalendarException(e);
        }
    }

}