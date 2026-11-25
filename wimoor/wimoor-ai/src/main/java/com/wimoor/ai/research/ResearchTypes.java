package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 研究模块共享 DTO 与枚举（移植自 src/lib/selection-research/types.ts）。
 * DTO 字段与源端 TypeScript interface 一一对应；JSON 值统一用 Jackson JsonNode 表达，
 * 金额/数量在领域类中用 BigDecimal/BigInteger，这里只承载已校验的原始结构。
 */
public final class ResearchTypes {

    private ResearchTypes() {}

    public static final List<String> SKILLS = List.of(
            "amazon-niche-finder", "amazon-product-research", "amazon-review-analyzer",
            "tariff-calculator-amazon", "amazon-product-selection");

    public static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "CANCELLED");

    public static final class Status {
        public static final String CREATED = "CREATED";
        public static final String RUNNING = "RUNNING";
        public static final String WAITING_FOR_USER = "WAITING_FOR_USER";
        public static final String RESUMABLE = "RESUMABLE";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";
        private Status() {}
    }

    public static final class GateKind {
        public static final String NICHE = "NICHE";
        public static final String FINAL = "FINAL";
        public static final String BUDGET = "BUDGET";
        public static final String MCP_CALL = "MCP_CALL";
        private GateKind() {}
    }

    public static final class DecisionKind {
        public static final String SELECT = "SELECT";
        public static final String CONFIRM = "CONFIRM";
        public static final String INCREASE_BUDGET = "INCREASE_BUDGET";
        public static final String AUTHORIZE_TOOL = "AUTHORIZE_TOOL";
        private DecisionKind() {}
    }

    /** 组织/工作区/所有者三元组；服务端从已验证身份构造，不接受请求头伪造。 */
    public static final class Scope {
        public String organizationId;
        public String workspaceId;
        public String ownerId;

        public Scope() {}
        public Scope(String organizationId, String workspaceId, String ownerId) {
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.ownerId = ownerId;
        }
    }

    public static final class SkillSnapshot {
        public String name;
        public String path;
        public String sha256;
        public String markdown;
        public List<String> tools = new ArrayList<>();

        public SkillSnapshot() {}
        public SkillSnapshot(String name, String path, String sha256, String markdown, List<String> tools) {
            this.name = name;
            this.path = path;
            this.sha256 = sha256;
            this.markdown = markdown;
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
        }
    }

    public static final class ToolDefinition {
        public String name;
        public String description;
        public String provider;
        public JsonNode inputSchema;
        public Boolean readOnlyHint;

        public ToolDefinition() {}
        public ToolDefinition(String name, JsonNode inputSchema) {
            this.name = name;
            this.inputSchema = inputSchema;
        }
    }

    /** 人工审阅后的策略：provider 付费单位用整数 provider units，不使用浮点金额。 */
    public static final class ToolPolicy {
        public String provider;
        public boolean paid;
        public int units;
        public String unit; // calls | credits
        public String pricingSource;
        public int maxCalls;
        public List<Integer> stages = new ArrayList<>();

        public ToolPolicy() {}
        public ToolPolicy(String provider, boolean paid, int units, String unit, String pricingSource, int maxCalls, List<Integer> stages) {
            this.provider = provider;
            this.paid = paid;
            this.units = units;
            this.unit = unit;
            this.pricingSource = pricingSource;
            this.maxCalls = maxCalls;
            this.stages = stages == null ? new ArrayList<>() : new ArrayList<>(stages);
        }
    }

    public static final class CallRecord {
        public String id;
        public String projectId;
        public int stage;
        public String skill;
        public String service;
        public String provider;
        public String tool;
        public JsonNode arguments;
        public String requestedAt;
        public String respondedAt;
        public String status; // IN_FLIGHT | SUCCEEDED | FAILED | UNKNOWN
        public ResearchCredits.ResearchCharge researchCharge;
        public String billingUnit;
        public boolean paid;
        public int reservedUnits;
        public String billing; // FREE | UNKNOWN | CONFIRMED
        public Integer knownUnits;
        public String authorizationId;
        public String authorizationMode; // FIRST_APPROVAL | TASK_APPROVAL
        public int retryCount;
        public JsonNode rawResponse;
        public String errorCode;
        public String dispatch; // NOT_SENT | ATTEMPTED
        public JsonNode inputSchema;
    }

    public static final class Claim {
        public String text;
        public String kind; // observed | calculated | estimated | assumption
        public List<String> evidenceIds = new ArrayList<>();
        public String formula;
        public String sourceUrl;
        public String applicableDate;
        public String topic; // tariff

        public Claim() {}
        public Claim(String text, String kind, List<String> evidenceIds) {
            this.text = text;
            this.kind = kind;
            this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
        }
    }

    public static final class Candidate {
        public String id;
        public String name;

        public Candidate() {}
        public Candidate(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class StageResult {
        public List<LandedCost.LandedCostScenario> landedCostScenarios;
        public Economics.DecisionAnalysis decisionAnalysis;
        public List<ReviewEvidenceModel.ReviewEvidence> reviewEvidence;
        public List<ReviewEvidenceModel.ReviewProblem> reviewProblems;
        public String summary;
        public List<Claim> claims = new ArrayList<>();
        public List<Candidate> candidates;
        public String verdict; // GO | NO_GO | NEEDS_VALIDATION

        public StageResult() {}
        public StageResult(String summary, List<Claim> claims) {
            this.summary = summary;
            this.claims = claims == null ? new ArrayList<>() : new ArrayList<>(claims);
        }
    }

    public static final class Gate {
        public String id;
        public String kind;
        public String reason;

        public Gate() {}
        public Gate(String id, String kind, String reason) {
            this.id = id;
            this.kind = kind;
            this.reason = reason;
        }
    }

    public static final class Decision {
        public String confirmationChannel;
        public String confirmationText;
        public String gateId;
        public String kind;
        public String candidateId;
        public Map<String, Integer> limits;
        public Map<String, Integer> toolMaxCalls;
        public String callId;
        public String authorizationId;

        public Decision() {}
    }

    public static final class PaidCallAuthorization {
        public String budgetMode;
        public String consentId;
        public String id;
        public String approvedAt;
        public String approvedByUserId;
        public String approvedActionDigest;
        public Map<String, Integer> providerLimits;
        public Map<String, Integer> toolLimits;
        public Map<String, String> toolSchemaDigests;
    }

    /** 一次研究任务的可持久化状态；与源端 Run interface 一一对应。 */
    public static final class ResearchRun {
        public BriefParser.ResearchBrief brief;
        public JsonNode validationFeedback;
        public String budgetMode;
        public String executionMode;
        public String aiConfigSource;
        public JsonNode aiModel;
        public String asin;
        public String erpCredentialId;
        public List<String> requiredProviders;
        public String pausedAt;
        public JsonNode planningPause;
        public List<JsonNode> creditAdjustments;
        public int version = 1;
        public String id;
        public int revision;
        public Scope scope;
        public String requirement;
        public String marketplace;
        public String mode; // live | fixture
        public String createdAt;
        public String updatedAt;
        public String status;
        public int stage;
        public List<SkillSnapshot> skills = new ArrayList<>();
        public List<ToolDefinition> registry = new ArrayList<>();
        public String registryAt;
        public Map<String, ToolPolicy> policies;
        public Map<String, Integer> limits;
        public List<CallRecord> calls = new ArrayList<>();
        public List<StageResult> results = new ArrayList<>();
        public Gate gate;
        public String selectedCandidateId;
        public String pendingActionId;
        public List<DecisionRecord> decisions = new ArrayList<>();
        public String failure;
        public List<ActionRecord> actions = new ArrayList<>();
        public Action pendingAction;
        public String pendingActionFingerprint;
        public Boolean pendingActionApproved;
        public PaidCallAuthorization paidCallAuthorization;
        public JsonNode report;
    }

    public static final class DecisionRecord {
        public String actorId;
        public String at;
        public Decision decision;

        public DecisionRecord() {}
        public DecisionRecord(String actorId, String at, Decision decision) {
            this.actorId = actorId;
            this.at = at;
            this.decision = decision;
        }
    }

    /** Action: {type:"call",tool,arguments} 或 {type:"complete",result}。 */
    public static final class Action {
        public String type;
        public String tool;
        public JsonNode arguments;
        public StageResult result;

        public Action() {}
        public static Action call(String tool, JsonNode arguments) {
            Action a = new Action();
            a.type = "call";
            a.tool = tool;
            a.arguments = arguments;
            return a;
        }
        public static Action complete(StageResult result) {
            Action a = new Action();
            a.type = "complete";
            a.result = result;
            return a;
        }
    }

    public static final class ActionRecord {
        public String at;
        public int stage;
        public Action action;

        public ActionRecord() {}
        public ActionRecord(String at, int stage, Action action) {
            this.at = at;
            this.stage = stage;
            this.action = action;
        }
    }

    public static final class ResearchContext {
        public ResearchRun run;
        public SkillSnapshot skill;
        public List<ToolDefinition> tools = new ArrayList<>();
        public String instructions;

        public ResearchContext() {}
        public ResearchContext(ResearchRun run, SkillSnapshot skill, List<ToolDefinition> tools, String instructions) {
            this.run = run;
            this.skill = skill;
            this.tools = tools;
            this.instructions = instructions;
        }
    }
}
