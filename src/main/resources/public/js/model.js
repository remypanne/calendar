model.colors = ['cyan', 'green', 'orange', 'pink', 'yellow', 'purple', 'grey'];
model.defaultColor = 'grey';

model.recurrence = {
    week_days: {
        1: false,
        2: false,
        3: false,
        4: false,
        5: false,
        6: false,
        7: false
    },
    dayMap: {
        1: "calendar.recurrence.daymap.mon",
        2: "calendar.recurrence.daymap.tue",
        3: "calendar.recurrence.daymap.wed",
        4: "calendar.recurrence.daymap.thu",
        5: "calendar.recurrence.daymap.fri",
        6: "calendar.recurrence.daymap.sat",
        7: "calendar.recurrence.daymap.sun"
    }
};

model.timeConfig = { // 5min slots from 7h00 to 19h55, default 8h00
    intervalTime: 5, // in minutes
    interval: 15, // in minutes
    start_hour: 7,
    end_hour: 20,
    default_hour: 8
};

model.periods = {
    every_day_max: 10,
    every_week_max: 10,
    every_month_max: 10,
    every_year_max: 10,
    periodicities: [1, 2, 3, 4], // weeks
    days: [
        1, // monday
        2, // tuesday
        3, // wednesday
        4, // thursday
        5, // friday
        6, // saturday
        0 // sunday
    ],
    occurrences: [] // loaded by function
};

model.periodsConfig = {
    occurrences: {
        start: 1,
        end: 52,
        interval: 1
    }
};

function CalendarEvent() {

}

CalendarEvent.prototype.save = function(callback){
    if (this.allday) {
        this.startMoment.hours(7);
        this.startMoment.minutes(0);
        this.endMoment.hours(20);
        this.endMoment.minutes(0);
    }
    if(this._id){
        this.update(callback);
    }
    else{
        this.create(callback);
    }
};

CalendarEvent.prototype.create = function(cb){
    var calendarEvent = this;
    http().postJson('/calendar/' + this.calendar._id + '/events', this).done(function(e){
        calendarEvent.updateData(e);
        if(typeof cb === 'function'){
            cb();
        }
    }.bind(this));
};


CalendarEvent.prototype.update = function(cb){
    var calendarEvent = this;
    http().putJson('/calendar/' + this.calendar._id + '/event/' + this._id, this).done(function(e){
        calendarEvent.updateData(e);
        if(typeof cb === 'function'){
            cb();
        }
    }.bind(this));
};

CalendarEvent.prototype.delete = function(callback) {
    http().delete('/calendar/' + this.calendar._id + '/event/' + this._id).done(function() {
        if(typeof callback === 'function'){
            callback();
        }
    }.bind(this));
};

CalendarEvent.prototype.calendarUpdate = function(cb, cbe) {
    if (this.end.diff(this.beginning, 'minutes') <= model.timeConfig.interval) {
        model.calendarEvents.trigger('refresh');
        model.currentEvent = this;
    }
    else{
        if (this.beginning) {
            var startMoment = moment(this.beginning).utc().milliseconds(0);
            var endMoment = moment(this.end).utc().milliseconds(0);
            var duration = endMoment.diff(startMoment, 'seconds');
            var intervalMinute = model.timeConfig.interval;
            var intervalSecond = intervalMinute * 60;
            if (duration%intervalSecond <= (intervalSecond/2)) {
                duration = ((duration/intervalSecond)>>0)*intervalSecond;
            } else {
                duration = (((duration+intervalSecond)/intervalSecond)>>0)*intervalSecond;
            }
            var startSecond = (startMoment.minutes() * 60) + startMoment.seconds();

            if (startSecond%intervalSecond <= (intervalSecond/2)) {
                var startMinute = ((startSecond/intervalSecond)>>0)*intervalMinute;
                startMoment.minutes(startMinute).seconds(0);
                startSecond = startSecond%intervalSecond;
            } else {
                var startMinute = (((startSecond + intervalSecond)/intervalSecond)>>0)*intervalMinute;
                startMoment.minutes(startMinute).seconds(0);
                startSecond = intervalSecond - startSecond%intervalSecond;
            }
            var endSecond = (endMoment.minutes() * 60) + endMoment.seconds();
            if (endSecond%intervalSecond <= (intervalSecond/2)) {
                var endMinute = ((endSecond/intervalSecond)>>0)*intervalMinute;
                endMoment.minutes(endMinute).seconds(0);
                endSecond = endSecond%intervalSecond;
            } else {
                var endMinute = (((endSecond + intervalSecond)/intervalSecond)>>0)*intervalMinute;
                endMoment.minutes(endMinute).seconds(0);
                endSecond = intervalSecond - endSecond%intervalSecond;
            }
            if (startSecond <= endSecond) {
                endMoment =  moment(startMoment).add(duration, 'seconds');
            } else {
                startMoment = moment(endMoment).subtract(duration, 'seconds');
            }
            this.startMoment = startMoment;
            this.endMoment = endMoment;
            this.beginning = startMoment;
            this.end = endMoment;
            //this.startMoment = this.beginning;
            //this.endMoment = this.end;
        }

        if(this._id) {

            this.update(function(){
                model.refresh();
            }, function(error){
                // notify
                model.refresh();
            });
        }
        else {
            this.create(function(){
                model.refresh();
            }, function(error){
                // notify
                model.refresh();
            });
        }
    }
}


CalendarEvent.prototype.toJSON = function(){

    return {
        title: this.title,
        description: this.description,
        location: this.location,
        startMoment: this.startMoment.second(0).millisecond(0),
        endMoment: this.endMoment.second(0).millisecond(0),
        allday: this.allday,
        recurrence: this.recurrence,
        parentId : this.parentId,
        isRecurrent: this.isRecurrent,
        index: this.index
    }
};


function Calendar() {
    var calendar = this;

    this.collection(CalendarEvent, {
        sync: function(callback){
            http().get('/calendar/' + calendar._id + '/events').done(function(calendarEvents){
                var locked = true;
                if (calendar.myRights.contrib) {
                    locked = false;
                }
                _.each(calendarEvents, function(calendarEvent){
                    calendarEvent.calendar = calendar;
                    //don't use timezone
                    var startDate = moment.utc(calendarEvent.startMoment).second(0).millisecond(0);
                    calendarEvent.startMoment = startDate;
                    calendarEvent.startMomentDate = startDate.format('DD/MM/YYYY');
                    calendarEvent.startMomentTime = startDate.format('hh:mm');
                    var endDate = moment.utc(calendarEvent.endMoment).second(0).millisecond(0);
                    calendarEvent.endMoment = endDate;
                    calendarEvent.endMomentDate = endDate.format('DD/MM/YYYY');
                    calendarEvent.endMomentTime = endDate.format('hh:mm');
                    calendarEvent.is_periodic = false;
                    calendarEvent.locked = locked;
                    calendarEvent.color = calendar.color;
                });
                this.load(calendarEvents);
                if(typeof callback === 'function'){
                    callback();
                }
            }.bind(this));
        },
        removeSelection: function(callback){
            var counter = this.selection().length;
            this.selection().forEach(function(item){
                http().delete('/calendar/' + calendar._id + '/event/' + item._id).done(function(){
                    counter = counter - 1;
                    if (counter === 0) {
                        Collection.prototype.removeSelection.call(this);
                        calendar.calendarEvents.sync();
                        if(typeof callback === 'function'){
                            callback();
                        }
                    }
                });
            });
        },
        behaviours: 'calendar'
    });
}

Calendar.prototype.save = function(callback) {
    if (this._id){
        this.update(callback);
    }
    else {
        this.create(callback);
    }
}

Calendar.prototype.create = function(callback){
    var calendar = this;
    http().postJson('/calendar/calendars', this).done(function(e){
        calendar.updateData(e);
        if(typeof callback === 'function'){
            callback();
        }
    }.bind(this));
};

Calendar.prototype.update = function(callback){
    var calendar = this;
    http().putJson('/calendar/' + this._id, this).done(function(e){
        calendar.updateData(e);
        if(typeof callback === 'function'){
            callback();
        }
    }.bind(this));
};

Calendar.prototype.delete = function(callback) {
    http().delete('/calendar/' + this._id).done(function() {
        model.calendars.remove(this);
        if(typeof callback === 'function'){
            callback();
        }
    }.bind(this));
}

Calendar.prototype.toJSON = function(){
    return {
        title: this.title,
        color: this.color
    }
};

Calendar.prototype.open = function(callback){
    this.calendarEvents.one('sync', function(){
        if(typeof callback === 'function'){
            callback();
        }
    }.bind(this));
    this.calendarEvents.sync();
};

model.build = function(){
    loader.loadFile('/calendar/public/js/additional.js');
    this.makeModel(Calendar);
    this.makeModel(CalendarEvent);

    Model.prototype.inherits(CalendarEvent, calendar.ScheduleItem);

    this.collection(Calendar, {
        sync: function(callback){
            var collection = this;
            http().get('/calendar/calendars').done(function(calendars){
                this.load(calendars);
                collection.trigger('sync');
                if(typeof callback === 'function'){
                    callback();
                }
            }.bind(this));
        },

        behaviours: 'calendar'
    });

    this.collection(CalendarEvent, {
        pushAll: function(datas, trigger) {
            if (datas) {
                this.all = _.union(this.all, datas);
                if (trigger) {
                    this.trigger('sync');
                }
            }
        },
        pullAll: function(datas, trigger) {
            if (datas) {
                this.all = _.difference(this.all, datas);
                if (trigger) {
                    this.trigger('sync');
                }
            }
        },
        removeCalendarEvents: function(calendar, trigger) {
            if (calendar) {
                var calendarEvents = [];
                this.all.forEach(function(item) {
                    if (item.calendar._id == calendar._id) {
                        calendarEvents.push(item);
                    }
                });
                this.pullAll(calendarEvents, trigger);
            }
        },
        getRecurrenceEvents: function(calendarEvent) {
            var calendarEvents = [];
            //var parentId = calendarEvent.parentId ? calendarEvent.parentId : calendarEvent._id;
            var parentId = calendarEvent.parentId ? calendarEvent.parentId : false;
            this.all.forEach(function(item) {
                //    if ((item.parentId && item.parentId === parentId) || item._id === parentId) {
                if (item.parentId && item.parentId === parentId) {
                    calendarEvents.push(item);
                }
            });
            if (calendarEvents.length == 1 && calendarEvents[0]._id == calendarEvent._id) {
                calendarEvents = [];
            }
            return calendarEvents;
        },
        clear: function(trigger) {
            this.all = [];
            if (trigger) {
                this.trigger('sync');
            }
        },
        applyFilters: function() {
            this.filtered = _.filter(this.all, function(calendarEvent){
                return calendarEvent.startMoment.isBefore(moment(model.calendarEvents.filters.endMoment).add(1,'day')) &&
                    calendarEvent.endMoment.isAfter(model.calendarEvents.filters.startMoment);
            });
        },

        filters: {
            mine: undefined,
            dates: undefined,
            startMoment: undefined,
            endMoment: undefined
        },
        filtered: [],
        behaviours: 'calendar'
    });
}

model.refresh = function() {
    model.calendarEvents.clear(true);
    model.calendars.selection().forEach(function(cl) {
        model.calendarEvents.pushAll(cl.calendarEvents.all);
    });
    model.calendarEvents.applyFilters();
};