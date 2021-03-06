/*
 * Copyright (c) 2002-2004
 * All rights reserved.
 */
package com.fdu.jira.plugin.report.timesheet;

import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.bc.issue.util.VisibilityValidator;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.datetime.DateTimeFormatter;
import com.atlassian.jira.datetime.DateTimeFormatterFactory;
import com.atlassian.jira.datetime.DateTimeStyle;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.comparator.IssueKeyComparator;
import com.atlassian.jira.issue.comparator.UserComparator;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.issue.worklog.Worklog;
import com.atlassian.jira.issue.worklog.WorklogManager;
import com.atlassian.jira.plugin.report.impl.AbstractReport;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.timezone.TimeZoneManager;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.user.util.UserUtil;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.ParameterUtils;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.action.ProjectActionSupport;
import com.atlassian.jira.web.bean.I18nBean;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.upm.license.storage.lib.ThirdPartyPluginLicenseStorageManager;
import com.fdu.jira.plugin.report.pivot.Pivot;
import com.fdu.jira.util.LicenseUtil;
import com.fdu.jira.util.TextUtil;
import com.fdu.jira.util.WeekPortletHeader;
import com.fdu.jira.util.WorklogUtil;
import com.atlassian.crowd.embedded.api.User;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;
import org.ofbiz.core.entity.DelegatorInterface;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericEntityException;
import org.ofbiz.core.entity.GenericValue;
import org.ofbiz.core.util.UtilMisc;

import java.io.UnsupportedEncodingException;
import java.sql.Timestamp;
import java.util.*;


/**
 * Generate a summary of worked hours in a specified period. The time period is
 * divided by the specifed value for display.
 */
public class TimeSheet extends AbstractReport {
    private static final Logger log = Logger.getLogger(TimeSheet.class);

    // A collection of interval start dates - correlating with the timeSpents
    // collection.    
    private List<WeekPortletHeader> weekDays = new ArrayList<WeekPortletHeader>();

    private Map<Issue, List<Worklog>> allWorkLogs = new Hashtable<Issue, List<Worklog>>();
    private Map<String, List<Worklog>> allWorklogsByUser = new LinkedHashMap<String, List<Worklog>>();
    private Map<User, Map<Issue, Map<Worklog, Long>>> weekWorkLog =
        new TreeMap<User, Map<Issue, Map<Worklog, Long>>>(new UserComparator());

    private Map<Issue, Map<Date, Long>> weekWorkLogShort =
        new TreeMap<Issue, Map<Date, Long>>(new IssueKeyComparator());
    private Map<User, Map<Date, Long>> userWorkLogShort =
        new TreeMap<User, Map<Date, Long>>(new UserComparator());
    private Map<Long, Long> weekTotalTimeSpents = new Hashtable<Long, Long>();
    private Map<User, Map<Long, Long>> userWeekTotalTimeSpents =
        new Hashtable<User, Map<Long, Long>>();
    private Map<User, Map<Issue, Long>> userIssueTotalTimeSpents =
        new Hashtable<User, Map<Issue, Long>>();
    private Map<Project, Map<Date, Long>> projectTimeSpents =
        new Hashtable<Project, Map<Date, Long>>();

    /**
    * <p>Variable that contains the project times grouped by a specific field.</p>
    * <p>This Variable represents/contains tree nested maps [project to [fieldname to [date to time]]]</p>
    */
    private Map<Project, Map<String, Map<Date, Long>>> projectGroupedByFieldTimeSpents =
        new Hashtable<Project, Map<String, Map<Date, Long>>>();
    private PermissionManager permissionManager;
    private IssueManager issueManager;
    private WorklogManager worklogManager;
    private UserUtil userUtil;
    private SearchProvider searchProvider;
    private VisibilityValidator visibilityValidator;
    private FieldVisibilityManager fieldVisibilityManager;
    private SearchRequestService searchRequestService;

    private final JiraAuthenticationContext authenticationContext;

    private final ApplicationProperties applicationProperties;

    private final DateTimeFormatterFactory dateTimeFormatterFactory;

    private TimeZoneManager timeZoneManager;

    private final ThirdPartyPluginLicenseStorageManager licenseManager;

    public Map<Long, Long> getWeekTotalTimeSpents() {
        return weekTotalTimeSpents;
    }

    public Map<User, Map<Date, Long>> getUserWorkLog() {
        return userWorkLogShort;
    }

    public Map<Issue, Map<Date, Long>> getWeekWorkLogShort() {
        return weekWorkLogShort;
    }

    public Map<User, Map<Issue, Map<Worklog, Long>>> getWeekWorkLog() {
        return weekWorkLog;
    }

    public List<WeekPortletHeader> getWeekDays() {
        return weekDays;
    }

    private int maxPeriod = 62; // 2 months in days

    public TimeSheet(DateTimeFormatterFactory dateTimeFormatterFactory,
            ApplicationProperties applicationProperties,
            PermissionManager permissionManager,
            WorklogManager worklogManager,
            IssueManager issueManager,
            SearchProvider searchProvider,
            VisibilityValidator visibilityValidator,
            FieldVisibilityManager fieldVisibilityManager,
            UserUtil userUtil,
            SearchRequestService searchRequestService,
            JiraAuthenticationContext authenticationContext,
            TimeZoneManager timeZoneManager,
            ThirdPartyPluginLicenseStorageManager licenseManager) {
        this.applicationProperties = applicationProperties;
        this.permissionManager = permissionManager;
        this.worklogManager = worklogManager;
        this.issueManager = issueManager;
        this.searchProvider = searchProvider;
        this.visibilityValidator = visibilityValidator;
        this.fieldVisibilityManager = fieldVisibilityManager;
        this.userUtil = userUtil;
        this.searchRequestService = searchRequestService;
        this.authenticationContext = authenticationContext;
        this.dateTimeFormatterFactory = dateTimeFormatterFactory;
        this.timeZoneManager = timeZoneManager;
        this.licenseManager = licenseManager;

        // maxPeriod limit
        String maxPeriodInDays = applicationProperties.getDefaultBackedString("jira.timesheet.plugin.maxPeriodInDays");
        if (maxPeriodInDays != null) {
            try {
                maxPeriod = Integer.valueOf(maxPeriodInDays);
            } catch (NumberFormatException e) {
                log.error("Invalid maxPeriodInDays number: " + maxPeriodInDays);
            }
        }
    }

    // Retrieve time user has spent for period
    public void getTimeSpents(User remoteUser, Date startDate, Date endDate, String targetUserName, 
    		boolean excelView, String priority, String[] targetGroups, Long projectId, Long filterId,
            Boolean showWeekends, Boolean showUsers, String groupByField)

            throws SearchException, GenericEntityException {

        JiraServiceContext jiraServiceContext = new JiraServiceContextImpl(remoteUser);
        TimeZone timezone = timeZoneManager.getLoggedInUserTimeZone();

        Set<Long> filteredIssues = new TreeSet<Long>();
        if (filterId != null) {
            log.info("Using filter: " + filterId);
            SearchRequest filter = searchRequestService.getFilter(jiraServiceContext, filterId);
            if (filter != null) { // not logged in
                SearchResults issues = searchProvider.search(filter.getQuery(), remoteUser, PagerFilter.getUnlimitedFilter());
                for (Iterator<Issue> i = issues.getIssues().iterator(); i.hasNext();) {
	                Issue value = i.next();
	                filteredIssues.add(value.getId());
                }
            }
        }


        EntityExpr startExpr = new EntityExpr("startdate",
                EntityOperator.GREATER_THAN_EQUAL_TO, new Timestamp(
                        startDate.getTime()));
        EntityExpr endExpr = new EntityExpr("startdate",
                EntityOperator.LESS_THAN, new Timestamp(endDate.getTime()));
        List<EntityExpr> entityExprs = UtilMisc.toList(startExpr, endExpr);
        Set<String> assigneeIds = null;
        if (targetGroups != null && targetGroups.length > 0
                && permissionManager.hasPermission(Permissions.USER_PICKER, remoteUser)) {
            Set<User> users = userUtil.getAllUsersInGroupNames(
                    Arrays.asList(targetGroups));
            assigneeIds = new TreeSet<String>();
            for (User user : users) {
                assigneeIds.add(user.getName());
                // TIME-156: risky :-\
                assigneeIds.add(user.getName().toLowerCase());
            }
            log.info("Searching worklogs created since '" + startDate
                + "', till '" + endDate + "', by group '" + Arrays.asList(targetGroups) + "'");
        } else {
            EntityExpr userExpr = new EntityExpr("author",
                EntityOperator.EQUALS, targetUserName);
            entityExprs.add(userExpr);

            log.info("Searching worklogs created since '" + startDate
                + "', till '" + endDate + "', by '" + targetUserName + "'");
        }

        List<String> orderBy = new ArrayList<String>();
        orderBy.add("author");
        orderBy.add("created");
        orderBy.add("issue");

        List<GenericValue> worklogs = ComponentManager.getComponent(DelegatorInterface.class).findByAnd(
                "Worklog", entityExprs, orderBy);

        List<Worklog> worklogObjects = new ArrayList<Worklog>();

        for (Iterator<GenericValue> worklogsIterator = worklogs.iterator();
                worklogsIterator.hasNext();) {
            GenericValue genericWorklog = worklogsIterator.next();
            Worklog worklog = WorklogUtil.convertToWorklog(genericWorklog, worklogManager, issueManager);

            boolean isValidVisibility = visibilityValidator.isValidVisibilityData(jiraServiceContext,
                    "worklog", worklog.getIssue(), worklog.getGroupLevel(),
                    worklog.getRoleLevelId() != null ? worklog.getRoleLevelId().toString() : null);
            if (!isValidVisibility) {
                continue;
            }

            Issue issue = issueManager.getIssueObject(
                    genericWorklog.getLong("issue"));
            
            if ((filterId != null && !filteredIssues.contains(issue.getId())) ||
                    (assigneeIds != null && !assigneeIds.contains(worklog.getAuthor()))) {
            	continue;
            }
            
            Project project = issue.getProjectObject();
            
            if (priority != null && priority.length() != 0
                    && !issue.getString("priority").equals(priority)) {
                continue; // exclude issues with other priorities than (if) selected
            }
            
            if (projectId != null && !project.getId().equals(projectId)){
            	continue; // exclude issues from other projects than (if) selected
            }
            
            User workedUser = userUtil.getUserObject(genericWorklog.getString("author"));
            
            if (workedUser == null) {
            	continue; // TIME-221: user may have been deleted
            }
            
            Date dateCreated = worklog.getStartDate();           
            
            Calendar cal = Calendar.getInstance(timezone);
            cal.setTimeInMillis(dateCreated.getTime());
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            Date dateOfTheDay = cal.getTime();
            Long dateCreatedLong  = cal.getTimeInMillis();

            WeekPortletHeader weekDay = new WeekPortletHeader(cal); // timeZone addjusted
            if(showWeekends != null && !showWeekends.booleanValue()  && weekDay.isNonBusinessDay()){
                continue; // exclude worklogs and issues that were started on weekends if no weekends desired to show
            }

            long spent;

            if (!permissionManager.hasPermission(Permissions.BROWSE, issue,
                    remoteUser)) {
                continue; // exclude issues that users can't (shouldn't be
                            // allowed to) see
            }

            if (excelView) {
                // excel view shows complete work log
                worklogObjects.add(worklog);
            } else {
                // html view shows summary hours per issue for each user
                // per entry (report)
            	
                // per issue (portlet)
                Map<Date, Long> weekTimeSpents = weekWorkLogShort.get(issue);
                if (weekTimeSpents == null) {
                    weekTimeSpents = new Hashtable<Date, Long>();
                    weekWorkLogShort.put(issue, weekTimeSpents); // portlet
                }
                
                spent = worklog.getTimeSpent();
                Long dateSpent = weekTimeSpents.get(dateOfTheDay);
                
                if (dateSpent != null) {
                    spent += dateSpent;
                }

                weekTimeSpents.put(dateOfTheDay, spent);

                // per user (group portlet)
                updateUserWorkLog(worklog, workedUser, dateOfTheDay);

                // per project per day                
                Map<Date, Long> projectWorkLog = projectTimeSpents.get(project);
                if(projectWorkLog == null){
                	projectWorkLog = new Hashtable<Date, Long>();
                	projectTimeSpents.put(project, projectWorkLog);
                }
                
                spent = worklog.getTimeSpent();
                
                Long projectSpent = projectWorkLog.get(dateOfTheDay);
                
                if(projectSpent != null){
                	spent += projectSpent;
                }
                
                projectWorkLog.put(dateOfTheDay, spent);

                // per project and field
                calculateTimesForProjectGroupedByField(groupByField, worklog,
                        issue, project, dateOfTheDay);

                // total per day
                spent = worklog.getTimeSpent();
                dateSpent = weekTotalTimeSpents.get(dateCreatedLong);
                if (dateSpent != null) {
                    spent += dateSpent;
                }
                weekTotalTimeSpents.put(dateCreatedLong, spent);
            	
                spent = worklog.getTimeSpent();
            	if(showUsers != null && showUsers.booleanValue()){ // is nul in portlet
	                Map<Issue, Map<Worklog, Long>> userWorkLog = weekWorkLog.get(workedUser);
	                if (userWorkLog == null) {
	                    userWorkLog = new TreeMap<Issue, Map<Worklog, Long>>(
	                            new IssueProjectComparator<Issue>());
	                    weekWorkLog.put(workedUser, userWorkLog);
	                }
	                Map<Worklog, Long> issueWorkLog = userWorkLog
	                        .get(issue);
	                if (issueWorkLog == null) {
	                    issueWorkLog = new Hashtable<Worklog, Long>();
	                    userWorkLog.put(issue, issueWorkLog);
	                }
	                issueWorkLog.put(worklog, spent);

	                // totals per user/week
	                Map<Long, Long> userTotalTimeSpents = userWeekTotalTimeSpents.get(workedUser);
	                if (userTotalTimeSpents == null) {
	                    userTotalTimeSpents = new HashMap<Long, Long>();
	                    userWeekTotalTimeSpents.put(workedUser, userTotalTimeSpents);
	                }
	                Long weekDaySpent = userTotalTimeSpents.get(dateCreatedLong);
	                if (weekDaySpent != null) {
	                    spent += weekDaySpent;
	                }            	
	                userTotalTimeSpents.put(dateCreatedLong, spent);

                        // totals per user/issue
	                spent = worklog.getTimeSpent();
	                Map<Issue, Long> issueTotalTimeSpents = userIssueTotalTimeSpents.get(workedUser);
                        if (issueTotalTimeSpents == null) {
                            issueTotalTimeSpents = new HashMap<Issue, Long>();
                            userIssueTotalTimeSpents.put(workedUser, issueTotalTimeSpents);
                        }
                        Long issueSpent = issueTotalTimeSpents.get(issue);
                        if (issueSpent != null) {
                            spent += issueSpent;
                        }               
                        issueTotalTimeSpents.put(issue, spent);
            	    }
            }
        }
        if (excelView)
            allWorklogsByUser = getWorklogMapByUser(worklogObjects);
        
        // fill dates (ordered list) and week days (corresponding to each date)
        Calendar calendarDate = Calendar.getInstance(timezone);
        calendarDate.setTimeInMillis(startDate.getTime());
        Calendar today = Calendar.getInstance(timezone);
        
        while (endDate.after(calendarDate.getTime())) {
        	WeekPortletHeader wph = new WeekPortletHeader((Calendar) calendarDate.clone());
        	
        	String businessDay = "";
        	if (calendarDate.get(Calendar.DATE) == today.get(Calendar.DATE) &&
        		calendarDate.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
        		calendarDate.get(Calendar.YEAR) == today.get(Calendar.YEAR))
        	{
        		businessDay = "toDay";
        	}
        	else if (wph.isNonBusinessDay() || wph.isHoliday())
        	{
        		businessDay = "nonBusinessDay";
        	}
        	
        	wph.setWeekDayCSS(businessDay); //rowHeaderDark redText red-highlight
        	
        	if(showWeekends == null || showWeekends.booleanValue()  || !wph.isNonBusinessDay()){ // check if allowed to show weekends and if this it is a weekend
        		weekDays.add(wph);
        	}
            calendarDate.add(Calendar.DAY_OF_YEAR, 1);
        }        
    }

    private Map<String, List<Worklog>> getWorklogMapByUser(List<Worklog> worklogObjects) {
        TreeMap<String, List<Worklog>> presult = new TreeMap<String, List<Worklog>>();
        for (Worklog w : worklogObjects) {
            List<Worklog> worklogs = presult.get(w.getAuthor());
            if (worklogs == null) {
                worklogs = new ArrayList<Worklog>();
                presult.put(w.getAuthor(), worklogs);
            }
            worklogs.add(w);
        }
        LinkedHashMap<String, List<Worklog>> result = new LinkedHashMap<String, List<Worklog>>();
        List<Map.Entry<String, List<Worklog>>> keyList = new ArrayList<Map.Entry<String, List<Worklog>>>(presult.entrySet());
        final UserManager userManager = ComponentAccessor.getUserManager();
        if ( userManager != null )
            Collections.sort(keyList, new Comparator<Map.Entry<String, List<Worklog>>>() {
                public int compare(Map.Entry<String, List<Worklog>> e1, Map.Entry<String, List<Worklog>> e2) {
                    User user1 = userManager.getUser(e1.getKey());
                    User user2 = userManager.getUser(e2.getKey());
                    String userFullName1 = (user1!=null)?user1.getDisplayName():e1.getKey();
                    String userFullName2 = (user1!=null)?user2.getDisplayName():e2.getKey();
                    return userFullName1.compareTo(userFullName2);
                }
            });
        for (Map.Entry<String, List<Worklog>> e : keyList) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }


    private void updateUserWorkLog(Worklog worklog, User workedUser, Date dateOfTheDay) {
        long spent;
        Map<Date, Long> dateToWorkMap =  userWorkLogShort.get(workedUser);
        if (dateToWorkMap == null) {
            dateToWorkMap = new HashMap<Date, Long>();
            userWorkLogShort.put(workedUser, dateToWorkMap); // portlet
        }

        spent = worklog.getTimeSpent();
        Long dateSpent = dateToWorkMap.get(dateOfTheDay);

        if (dateSpent != null) {
            spent += dateSpent;
        }
        dateToWorkMap.put(dateOfTheDay, spent);
    }
    
    /**
     * Calculates the times spend per project, grouped by a field.
     * <p/>
     * Stores results in {@link #projectGroupedByFieldTimeSpents}.
     *
     * @param groupByFieldID - id of the field the group by should base on
     * @param worklog        - reference to for-loop variable from @see #getTimeSpents
     * @param issue          - reference to for-loop variable from @see #getTimeSpents
     * @param project        - reference to for-loop variable from @see #getTimeSpents
     * @param dateOfTheDay   - reference to for-loop variable from @see #getTimeSpents
     */
    private void calculateTimesForProjectGroupedByField(String groupByFieldID,
            Worklog worklog, Issue issue, Project project, Date dateOfTheDay) {
        long spent;

        if (groupByFieldID != null) {

            // Set field value
            String fieldValue = TextUtil.getFieldValue(groupByFieldID, issue,
                    dateTimeFormatterFactory.formatter().forLoggedInUser());

            // dimension Project to field
            Map<String, Map<Date, Long>> projectToFieldWorkLog =
               projectGroupedByFieldTimeSpents.get(project);
            if (projectToFieldWorkLog == null) {
                projectToFieldWorkLog = new Hashtable<String, Map<Date, Long>>();
                projectGroupedByFieldTimeSpents.put(project,
                        projectToFieldWorkLog);
            }

            // dimension Field to Time
            Map<Date, Long> fieldToTimeWorkLog = projectToFieldWorkLog
                    .get(fieldValue);
            if (fieldToTimeWorkLog == null) {
                fieldToTimeWorkLog = new Hashtable<Date, Long>();
                projectToFieldWorkLog.put(fieldValue, fieldToTimeWorkLog);
            }

            spent = worklog.getTimeSpent();
            Long projectGroupedSpent = fieldToTimeWorkLog
                    .get(dateOfTheDay);
            if (projectGroupedSpent != null) {
                spent += projectGroupedSpent;
            }

            fieldToTimeWorkLog.put(dateOfTheDay, spent);
        }
    }

    // Generate the report
    public String generateReport(ProjectActionSupport action, Map params,
        boolean excelView) throws Exception {
        User remoteUser = authenticationContext.getLoggedInUser();
        I18nBean i18nBean = new I18nBean(remoteUser);
        TimeZone timezone = timeZoneManager.getLoggedInUserTimeZone();

        // Retrieve the start and end date specified by the user
        Date endDate = Pivot.getEndDate(params, i18nBean, timezone);
        Date startDate = Pivot.getStartDate(params, i18nBean, endDate, timezone);
                
        User targetUser = ParameterUtils.getUserParam(params, "targetUser");
        String priority = ParameterUtils.getStringParam(params, "priority");
        String[] targetGroups = ParameterUtils.getStringArrayParam(params, "targetGroup");
        if (targetGroups != null) try {
            for (int i = 0; i < targetGroups.length; ++i) {
                // TIME-158 workaround: this must be handled by application server instead or this may be bug in Jira gadgets
                String targetGroup = new String(StringEscapeUtils.unescapeHtml(targetGroups[i]).getBytes("ISO-8859-1"),
                        applicationProperties.getEncoding());
                targetGroups[i] = targetGroup;
            }
        } catch (UnsupportedEncodingException e) {
            // safe to ignore
        }        

        Long projectId = null; 
        if(!"".equals(ParameterUtils.getStringParam(params, "project"))){
        	projectId = ParameterUtils.getLongParam(params, "project");
        }
        
        Long filterId = ParameterUtils.getLongParam(params, "filterid");
        
        Boolean showWeekends = null;
        if ("true".equalsIgnoreCase(ParameterUtils.getStringParam(params, "weekends"))) 
        	showWeekends=new Boolean (true); 
        else 
        	showWeekends=new Boolean (false);
        
        Boolean showUsers = null;
        if ("true".equalsIgnoreCase(ParameterUtils.getStringParam(params, "showUsers"))) 
        	showUsers=new Boolean (true); 
        else 
        	showUsers=new Boolean (false);
        
        if (targetUser == null) {
            targetUser = remoteUser;
        }

        // read ID of the 'group by' field from parameter map
        String groupByField = ParameterUtils.getStringParam(params, "groupByField");
        if (groupByField != null) {
            if (groupByField.trim().length() == 0) {
                groupByField = null;
            }  else if (ComponentManager.getComponentInstanceOfType(
                    FieldManager.class).getField(groupByField) == null) {
                log.error("GroupByField ' " + groupByField + "' does not exist");
                groupByField = null;
            }
        }

        // get time spents
        if (remoteUser != null) {
            getTimeSpents(remoteUser, startDate, endDate, targetUser.getName(),
                    excelView, priority, targetGroups, projectId, filterId, showWeekends,
                    showUsers, groupByField);
        }

        // Pass the issues to the velocity template
        Map<String, Object> velocityParams = new HashMap<String, Object>();
        velocityParams.put("startDate", startDate);
        velocityParams.put("endDate", endDate);
        velocityParams.put("weekDays", weekDays);
        velocityParams.put("targetUser", targetUser);
        velocityParams.put("priority", priority);
        velocityParams.put("targetGroup", Arrays.toString(targetGroups));
        velocityParams.put("projectId", projectId);
        velocityParams.put("filterId", filterId);
        velocityParams.put("showWeekends", showWeekends);
        velocityParams.put("showUsers", showUsers);
        velocityParams.put("groupByField", groupByField);
        List<String> moreFields = ParameterUtils.getListParam(params, "moreFields");
        if (moreFields == null) {
            moreFields = new ArrayList<String>();
            String moreField = ParameterUtils.getStringParam(params, "moreFields");
            if (moreField != null && !moreFields.isEmpty()) {
                moreFields.add(moreField);
            }
        }
        velocityParams.put("moreFields", moreFields);

        
        if (excelView) {
            velocityParams.put("allWorklogsByUser", allWorklogsByUser);
            //velocityParams.put("allWorkLogs", allWorkLogs);
        } else {
            if (showUsers.booleanValue()) {
                velocityParams.put("weekWorkLog", weekWorkLog);
            } else {
                velocityParams.put("weekWorkLog", weekWorkLogShort);
            }
            velocityParams.put("weekTotalTimeSpents", weekTotalTimeSpents);
            velocityParams.put("userWeekTotalTimeSpents", userWeekTotalTimeSpents);
            velocityParams.put("userIssueTotalTimeSpents", userIssueTotalTimeSpents);
            velocityParams.put("projectTimeSpents", projectTimeSpents);
            velocityParams.put("projectGroupedTimeSpents", projectGroupedByFieldTimeSpents);
        }
        velocityParams.put("groupByField", groupByField);
        DateTimeFormatter outlookDate = dateTimeFormatterFactory.
        formatter().withStyle(DateTimeStyle.DATE).forLoggedInUser().withZone(timezone);
        velocityParams.put("outlookDate", outlookDate);
        velocityParams.put("fieldVisibility", fieldVisibilityManager);
        velocityParams.put("textUtil", new TextUtil(i18nBean, timezone));
        velocityParams.put("license", LicenseUtil.getStatus(licenseManager));

        return descriptor.getHtml(excelView ? "excel" : "view",
                velocityParams);
    }

    // Validate the parameters set by the user.
    public void validate(ProjectActionSupport action, Map params) {
        // nothing to do, all parameters are optional,
        // and there is no longer restriction for one month period
        
        User remoteUser = authenticationContext.getLoggedInUser();
        I18nHelper i18nBean = new I18nBean(remoteUser);

        // startDate before endDate

        Date startDate = ParameterUtils.getDateParam(params, "startDate",
                i18nBean.getLocale());
        Date endDate = ParameterUtils.getDateParam(params, "endDate",
                i18nBean.getLocale());

        if (startDate == null || endDate == null) {
            return; // nothing to validate
        }

        // The end date must be after the start date
        if (endDate.before(startDate)) {
            action.addError("endDate", action
                    .getText("report.timesheet.before.startdate"));
        }

        // maxPeriod

        Calendar c = Calendar.getInstance();
        c.setTime(startDate);
        c.add(Calendar.DAY_OF_MONTH, maxPeriod);

        // The end date must be after the start date
        if (c.getTime().before(endDate)) {
            action.addError("endDate", action
                    .getText("report.timesheet.maxperiod"));
        }
    }

    public boolean isExcelViewSupported() {
        return true;
    }

    // Generate html report
    public String generateReportHtml(ProjectActionSupport action, Map params) throws Exception {
        return generateReport(action, params, false);
    }

    // Generate excel, report
    public String generateReportExcel(ProjectActionSupport action, Map params) throws Exception {
        return generateReport(action, params, true);
    }

}
