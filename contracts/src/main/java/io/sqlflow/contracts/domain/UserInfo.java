package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * User identity, profile, and role DTO aligned 1:1 with {@code UserVo} and {@code JobUser}
 * ({@code 00 §3.3 #11}, {@code 01 §3.1, §3.3}, {@code 02 §5.6}, {@code 04 §12.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "userId",
    "userName",
    "userEmail",
    "userSource",
    "userGroup",
    "userRole",
    "registerDate",
    "latestLoginDate",
    "createDate",
    "userType",
    "beginDate",
    "endDate",
    "remainingSqls",
    "flag",
    "userIds",
    "group",
    "groupUserIds",
    "secretKey"
})
public class UserInfo {

    /** Canonical built-in anonymous guest identity ({@code 00 §3.3 #11}). */
    public static final String GUEST_USER_ID = "gudu|0123456789";

    /** Canonical built-in default admin identity ({@code MD5("admin")}). */
    public static final String DEFAULT_ADMIN_USER_ID = "21232f297a57a5a743894a0e4a801fc3";

    @JsonAlias({"id", "userId"})
    private String userId;

    @JsonAlias({"name", "userName"})
    private String userName;

    @JsonAlias({"email", "userEmail"})
    private String userEmail;

    private String userSource;
    private String userGroup;

    @JsonAlias({"role", "userRole"})
    private String userRole;

    private String registerDate;

    @JsonAlias({"lastLoginTime", "latestLoginDate"})
    private String latestLoginDate;

    @JsonAlias({"createTime", "createDate"})
    private String createDate;

    private String userType;
    private String beginDate;
    private String endDate;
    private String remainingSqls;
    private String flag;

    // JobUser & extended profile fields
    private List<String> userIds;
    private String group;
    private List<String> groupUserIds;
    private String secretKey;
    private String company;
    private String picture;
    private Boolean active;

    public UserInfo() {
    }

    public static UserInfo guest() {
        UserInfo guest = new UserInfo();
        guest.setUserId(GUEST_USER_ID);
        guest.setUserName("anonymous");
        guest.setUserSource("default");
        guest.setUserRole("3");
        guest.setUserType("standard");
        guest.setFlag("0");
        return guest;
    }

    public String getUserId() {
        return userId;
    }

    @JsonAlias({"id", "userId"})
    public void setUserId(String userId) {
        this.userId = userId;
    }

    @JsonIgnore
    public String getId() {
        return userId;
    }

    @JsonIgnore
    public void setId(String id) {
        this.userId = id;
    }

    public String getUserName() {
        return userName;
    }

    @JsonAlias({"name", "userName"})
    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    @JsonAlias({"email", "userEmail"})
    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    @JsonIgnore
    public String getEmail() {
        return userEmail;
    }

    @JsonIgnore
    public void setEmail(String email) {
        this.userEmail = email;
    }

    public String getUserSource() {
        return userSource;
    }

    public void setUserSource(String userSource) {
        this.userSource = userSource;
    }

    public String getUserGroup() {
        return userGroup;
    }

    public void setUserGroup(String userGroup) {
        this.userGroup = userGroup;
    }

    public String getUserRole() {
        return userRole;
    }

    @JsonAlias({"role", "userRole"})
    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    @JsonIgnore
    public String getRole() {
        return userRole;
    }

    @JsonIgnore
    public void setRole(String role) {
        this.userRole = role;
    }

    public String getRegisterDate() {
        return registerDate;
    }

    public void setRegisterDate(String registerDate) {
        this.registerDate = registerDate;
    }

    public String getLatestLoginDate() {
        return latestLoginDate;
    }

    @JsonAlias({"lastLoginTime", "latestLoginDate"})
    public void setLatestLoginDate(String latestLoginDate) {
        this.latestLoginDate = latestLoginDate;
    }

    public String getCreateDate() {
        return createDate;
    }

    @JsonAlias({"createTime", "createDate"})
    public void setCreateDate(String createDate) {
        this.createDate = createDate;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getBeginDate() {
        return beginDate;
    }

    public void setBeginDate(String beginDate) {
        this.beginDate = beginDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getRemainingSqls() {
        return remainingSqls;
    }

    public void setRemainingSqls(String remainingSqls) {
        this.remainingSqls = remainingSqls;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public List<String> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<String> userIds) {
        this.userIds = userIds;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public List<String> getGroupUserIds() {
        return groupUserIds;
    }

    public void setGroupUserIds(List<String> groupUserIds) {
        this.groupUserIds = groupUserIds;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
