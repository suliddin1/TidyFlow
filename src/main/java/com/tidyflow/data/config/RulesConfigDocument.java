package com.tidyflow.data.config;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.tidyflow.core.rules.Action;
import com.tidyflow.core.rules.Condition;
import com.tidyflow.core.rules.ExtensionCondition;
import com.tidyflow.core.rules.MinSizeCondition;
import com.tidyflow.core.rules.MoveAction;
import com.tidyflow.core.rules.OlderThanDaysCondition;
import com.tidyflow.core.rules.RegexFilenameCondition;
import com.tidyflow.core.rules.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Jackson serialization DTOs for rules.json.
 */
public final class RulesConfigDocument {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private List<RuleDto> rules = new ArrayList<>();

    public RulesConfigDocument() {
    }

    public RulesConfigDocument(int version, List<RuleDto> rules) {
        this.version = version;
        this.rules = rules;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<RuleDto> getRules() {
        return rules;
    }

    public void setRules(List<RuleDto> rules) {
        this.rules = rules;
    }

    public static RulesConfigDocument fromDomain(List<Rule> domainRules) {
        List<RuleDto> dtos = domainRules.stream()
                .map(RuleDto::fromDomain)
                .toList();
        return new RulesConfigDocument(CURRENT_VERSION, dtos);
    }

    public List<Rule> toDomain() {
        if (rules == null) {
            throw new IllegalArgumentException("Missing 'rules' array in config");
        }
        List<Rule> result = new ArrayList<>(rules.size());
        for (RuleDto dto : rules) {
            result.add(dto.toDomain());
        }
        return result;
    }

    public static final class RuleDto {
        private String name;
        private boolean enabled;
        private int priority;
        private List<ConditionDto> conditions;
        private ActionDto action;

        public RuleDto() {
        }

        public RuleDto(String name, boolean enabled, int priority, List<ConditionDto> conditions, ActionDto action) {
            this.name = name;
            this.enabled = enabled;
            this.priority = priority;
            this.conditions = conditions;
            this.action = action;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public List<ConditionDto> getConditions() {
            return conditions;
        }

        public void setConditions(List<ConditionDto> conditions) {
            this.conditions = conditions;
        }

        public ActionDto getAction() {
            return action;
        }

        public void setAction(ActionDto action) {
            this.action = action;
        }

        public static RuleDto fromDomain(Rule rule) {
            List<ConditionDto> condDtos = rule.conditions().stream()
                    .map(ConditionDto::fromDomain)
                    .toList();
            ActionDto actDto = ActionDto.fromDomain(rule.action());
            return new RuleDto(rule.name(), rule.enabled(), rule.priority(), condDtos, actDto);
        }

        public Rule toDomain() {
            if (name == null || conditions == null || action == null) {
                throw new IllegalArgumentException("Incomplete rule definition in JSON");
            }
            List<Condition> domainConds = conditions.stream()
                    .map(ConditionDto::toDomain)
                    .toList();
            return new Rule(name, enabled, priority, domainConds, action.toDomain());
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ExtensionListConditionDto.class, name = "EXTENSION_LIST"),
            @JsonSubTypes.Type(value = FilenameRegexConditionDto.class, name = "FILENAME_REGEX"),
            @JsonSubTypes.Type(value = MinSizeConditionDto.class, name = "MIN_SIZE"),
            @JsonSubTypes.Type(value = OlderThanDaysConditionDto.class, name = "OLDER_THAN_DAYS")
    })
    public sealed interface ConditionDto permits
            ExtensionListConditionDto,
            FilenameRegexConditionDto,
            MinSizeConditionDto,
            OlderThanDaysConditionDto {

        Condition toDomain();

        static ConditionDto fromDomain(Condition condition) {
            if (condition instanceof ExtensionCondition ext) {
                return new ExtensionListConditionDto(ext.extensionList());
            } else if (condition instanceof RegexFilenameCondition rx) {
                return new FilenameRegexConditionDto(rx.regex());
            } else if (condition instanceof MinSizeCondition sz) {
                return new MinSizeConditionDto(sz.minBytes());
            } else if (condition instanceof OlderThanDaysCondition od) {
                return new OlderThanDaysConditionDto(od.days());
            }
            throw new IllegalArgumentException("Unsupported condition type: " + condition.getClass());
        }
    }

    public static final class ExtensionListConditionDto implements ConditionDto {
        private List<String> extensions;

        public ExtensionListConditionDto() {
        }

        public ExtensionListConditionDto(List<String> extensions) {
            this.extensions = extensions;
        }

        public List<String> getExtensions() {
            return extensions;
        }

        public void setExtensions(List<String> extensions) {
            this.extensions = extensions;
        }

        @Override
        public Condition toDomain() {
            if (extensions == null) {
                throw new IllegalArgumentException("Missing extensions list");
            }
            return new ExtensionCondition(extensions);
        }
    }

    public static final class FilenameRegexConditionDto implements ConditionDto {
        private String regex;

        public FilenameRegexConditionDto() {
        }

        public FilenameRegexConditionDto(String regex) {
            this.regex = regex;
        }

        public String getRegex() {
            return regex;
        }

        public void setRegex(String regex) {
            this.regex = regex;
        }

        @Override
        public Condition toDomain() {
            if (regex == null) {
                throw new IllegalArgumentException("Missing regex pattern");
            }
            return new RegexFilenameCondition(regex);
        }
    }

    public static final class MinSizeConditionDto implements ConditionDto {
        private long minBytes;

        public MinSizeConditionDto() {
        }

        public MinSizeConditionDto(long minBytes) {
            this.minBytes = minBytes;
        }

        public long getMinBytes() {
            return minBytes;
        }

        public void setMinBytes(long minBytes) {
            this.minBytes = minBytes;
        }

        @Override
        public Condition toDomain() {
            return new MinSizeCondition(minBytes);
        }
    }

    public static final class OlderThanDaysConditionDto implements ConditionDto {
        private long days;

        public OlderThanDaysConditionDto() {
        }

        public OlderThanDaysConditionDto(long days) {
            this.days = days;
        }

        public long getDays() {
            return days;
        }

        public void setDays(long days) {
            this.days = days;
        }

        @Override
        public Condition toDomain() {
            return new OlderThanDaysCondition(days);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MoveActionDto.class, name = "MOVE")
    })
    public sealed interface ActionDto permits MoveActionDto {
        Action toDomain();

        static ActionDto fromDomain(Action action) {
            if (action instanceof MoveAction move) {
                return new MoveActionDto(move.rawTargetFolder());
            }
            throw new IllegalArgumentException("Unsupported action type: " + action.getClass());
        }
    }

    public static final class MoveActionDto implements ActionDto {
        private String targetFolder;

        public MoveActionDto() {
        }

        public MoveActionDto(String targetFolder) {
            this.targetFolder = targetFolder;
        }

        public String getTargetFolder() {
            return targetFolder;
        }

        public void setTargetFolder(String targetFolder) {
            this.targetFolder = targetFolder;
        }

        @Override
        public Action toDomain() {
            if (targetFolder == null) {
                throw new IllegalArgumentException("Missing targetFolder in MOVE action");
            }
            return new MoveAction(targetFolder);
        }
    }
}
