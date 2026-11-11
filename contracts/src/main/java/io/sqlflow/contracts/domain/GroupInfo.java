package io.sqlflow.contracts.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * User group and group membership DTO aligned 1:1 with {@code GroupVo} & {@code GroupUser}
 * ({@code 02 §5.6}, {@code 04 §12.4}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "groupId",
    "userGroup",
    "groupName",
    "createdDate",
    "expiredDate",
    "quote",
    "groupUsers",
    "description",
    "createUserId"
})
public class GroupInfo {

    @JsonAlias({"id", "groupId"})
    private String groupId;

    private String userGroup;
    private String groupName;

    @JsonAlias({"createTime", "createdDate"})
    private String createdDate;

    @JsonAlias({"expireDate", "endDate", "expiredDate"})
    private String expiredDate;

    private String quote;

    @JsonAlias({"users", "groupUsers"})
    private List<GroupMember> groupUsers;

    private String description;
    private String createUserId;

    public GroupInfo() {
    }

    public String getGroupId() {
        return groupId;
    }

    @JsonAlias({"id", "groupId"})
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getUserGroup() {
        return userGroup;
    }

    public void setUserGroup(String userGroup) {
        this.userGroup = userGroup;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    @JsonAlias({"createTime", "createdDate"})
    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public String getExpiredDate() {
        return expiredDate;
    }

    @JsonAlias({"expireDate", "endDate", "expiredDate"})
    public void setExpiredDate(String expiredDate) {
        this.expiredDate = expiredDate;
    }

    public String getQuote() {
        return quote;
    }

    public void setQuote(String quote) {
        this.quote = quote;
    }

    public List<GroupMember> getGroupUsers() {
        return groupUsers;
    }

    @JsonAlias({"users", "groupUsers"})
    public void setGroupUsers(List<GroupMember> groupUsers) {
        this.groupUsers = groupUsers;
    }

    @JsonIgnore
    public List<GroupMember> getUsers() {
        return groupUsers;
    }

    @JsonIgnore
    public void setUsers(List<GroupMember> users) {
        this.groupUsers = users;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(String createUserId) {
        this.createUserId = createUserId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"userEmail", "userRole", "userId", "userName", "groupAdmin"})
    public static class GroupMember {
        @JsonAlias({"email", "userEmail"})
        private String userEmail;

        @JsonAlias({"role", "userRole"})
        private String userRole;

        private String userId;
        private String userName;
        private Boolean groupAdmin;

        public GroupMember() {
        }

        public GroupMember(String userEmail, String userRole) {
            this.userEmail = userEmail;
            this.userRole = userRole;
        }

        public String getUserEmail() {
            return userEmail;
        }

        @JsonAlias({"email", "userEmail"})
        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }

        public String getUserRole() {
            return userRole;
        }

        @JsonAlias({"role", "userRole"})
        public void setUserRole(String userRole) {
            this.userRole = userRole;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Boolean getGroupAdmin() {
            return groupAdmin;
        }

        public void setGroupAdmin(Boolean groupAdmin) {
            this.groupAdmin = groupAdmin;
        }
    }
}
