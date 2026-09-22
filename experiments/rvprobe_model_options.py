"""RVProbe generation options; never alter HAVEN requests."""
import argparse

DEFAULT_REASONING_EFFORT='max'
REASONING_EFFORTS=('low','high','max')
QUALITY_POLICY = 'coverage-constrained-generation-v2'
QUALITY_MAX_TOKENS = 65536
QUALITY_EVIDENCE_STEPS = 1
QUALITY_EVIDENCE_TOOLS = 8
QUALITY_RETRIEVAL_MAX_TOKENS = 16384


def token_limit(value):
    number=int(value)
    if isinstance(value,bool) or not 1<=number<=393216:
        raise argparse.ArgumentTypeError('max_tokens must be between 1 and 393216')
    return number


def positive_seconds(value):
    number=int(value)
    if number<1:raise argparse.ArgumentTypeError('request timeout must be positive')
    return number


def nonnegative_integer(value):
    number=int(value)
    if number < 0:
        raise argparse.ArgumentTypeError('value must be nonnegative')
    return number


def positive_integer(value):
    number=int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('value must be positive')
    return number


def intent_batch_limit(value):
    number = int(value)
    if number < 1 or number > 8:
        raise argparse.ArgumentTypeError('intent batch limit must be between 1 and 8')
    return number


def coverage_floor(value):
    number=float(value)
    if not 0 < number <= 100:
        raise argparse.ArgumentTypeError('coverage floor must be in (0, 100]')
    return number


def add_options(parser):
    parser.add_argument('--rvprobe-model',
        help='RVProbe-only provider model ID; omitted inherits --model, never changes HAVEN')
    parser.add_argument('--rvprobe-feedback-mode',choices=('full','no_diagnostics','no_coverage'))
    parser.add_argument('--rvprobe-fixed-rounds',action='store_true',
        help='Controlled ablation: do not stop on coverage gain, target, or an empty round')
    parser.add_argument('--rvprobe-dialogue-policy', choices=('staged','incremental'),
        help='RVProbe only: explicit controlled dialogue variant; omitted preserves staged baseline')
    parser.add_argument('--rvprobe-reasoning-effort',choices=REASONING_EFFORTS,default=DEFAULT_REASONING_EFFORT,
        help='RVProbe only: DeepSeek reasoning effort (default: max)')
    parser.add_argument('--rvprobe-max-tokens',type=token_limit,
        help='RVProbe only: total generated tokens including reasoning; omitted means provider default')
    parser.add_argument('--rvprobe-request-timeout',type=positive_seconds,
        help='RVProbe only: per HTTP request wall timeout; omitted preserves existing timeout')
    parser.add_argument('--rvprobe-evidence-steps',type=nonnegative_integer,
        help='RVProbe only: maximum model requests that may retrieve evidence before a final LTL request')
    parser.add_argument('--rvprobe-evidence-tools',type=positive_integer,
        help='RVProbe only: maximum executed RTL/coverage tool entries per generation dialogue')
    parser.add_argument('--rvprobe-retrieval-max-tokens',type=token_limit,
        help='RVProbe only: token limit for evidence/tool requests; final LTL requests use --rvprobe-max-tokens')
    parser.add_argument('--rvprobe-retrieval-reasoning-effort',choices=REASONING_EFFORTS,
        help='RVProbe only: reasoning effort for evidence/tool requests; final LTL uses --rvprobe-reasoning-effort')
    parser.add_argument('--rvprobe-intent-batch-limit',type=intent_batch_limit,default=4,
        help='RVProbe only: maximum new Gen intents in one coverage round (HAVEN remains fixed at 4)')
    parser.add_argument('--rvprobe-adaptive-quality-floor',type=coverage_floor,
        help='RVProbe only: require this final composite coverage; below it, rounds after the first use the recorded coverage-constrained generation policy')


def cli(args):
    result=[]
    for name in ('rvprobe_model','rvprobe_max_tokens','rvprobe_request_timeout','rvprobe_reasoning_effort','rvprobe_dialogue_policy','rvprobe_feedback_mode','rvprobe_evidence_steps','rvprobe_evidence_tools','rvprobe_retrieval_max_tokens','rvprobe_retrieval_reasoning_effort','rvprobe_adaptive_quality_floor'):
        value=getattr(args,name,None)
        if value is not None:result+=['--'+name.replace('_','-'),str(value)]
    if getattr(args,'rvprobe_intent_batch_limit',4) != 4:
        result += ['--rvprobe-intent-batch-limit',str(args.rvprobe_intent_batch_limit)]
    if getattr(args,'rvprobe_fixed_rounds',False):result.append('--rvprobe-fixed-rounds')
    return result


def model_for(args, arm='rvprobe'):
    shared=getattr(args,'model',None) or 'deepseek-v4-flash-vision-exp'
    return (getattr(args,'rvprobe_model',None) or shared) if arm=='rvprobe' else shared


def record(args):
    return dict(model=model_for(args),
        feedback_mode=getattr(args,'rvprobe_feedback_mode',None) or 'full',
        fixed_rounds=getattr(args,'rvprobe_fixed_rounds',False),
        dialogue_policy=getattr(args,'rvprobe_dialogue_policy',None) or 'staged',
        max_tokens=getattr(args,'rvprobe_max_tokens',None),
        request_timeout_seconds=getattr(args,'rvprobe_request_timeout',None) or getattr(args,'timeout',600),
        evidence_steps=getattr(args,'rvprobe_evidence_steps',None),
        evidence_tools=getattr(args,'rvprobe_evidence_tools',None),
        retrieval_max_tokens=getattr(args,'rvprobe_retrieval_max_tokens',None),
        retrieval_reasoning_effort=getattr(args,'rvprobe_retrieval_reasoning_effort',None),
        intent_batch_limit=getattr(args,'rvprobe_intent_batch_limit',4),
        adaptive_quality_floor=getattr(args,'rvprobe_adaptive_quality_floor',None),
        adaptive_quality_policy=(dict(policy=QUALITY_POLICY,first_round='quality options when baseline is below floor',
            trigger='current composite coverage is below the floor, including round 1',
            max_tokens=QUALITY_MAX_TOKENS,reasoning_effort='high',
            evidence_steps=QUALITY_EVIDENCE_STEPS,evidence_tools=QUALITY_EVIDENCE_TOOLS,
            retrieval_max_tokens=QUALITY_RETRIEVAL_MAX_TOKENS,
            retrieval_reasoning_effort='low')
            if getattr(args,'rvprobe_adaptive_quality_floor',None) is not None else None),
        token_limit_semantics='reasoning plus final output; no separately reserved answer budget',
        reasoning_effort=getattr(args,'rvprobe_reasoning_effort',DEFAULT_REASONING_EFFORT))


def effective(args, feedback, round_number):
    """Return auditable per-round options; never inspect a design name."""
    options=record(args)
    floor=options['adaptive_quality_floor']
    score=feedback.get('score') if isinstance(feedback,dict) else None
    escalated=(floor is not None and isinstance(score,(int,float)) and score < floor)
    options['quality_escalated']=escalated
    options['coverage_before_round']=score
    options['coverage_round']=round_number
    if not escalated:
        return options
    effort_rank={name:index for index,name in enumerate(REASONING_EFFORTS)}
    options.update(
        max_tokens=max(options['max_tokens'] or 0,QUALITY_MAX_TOKENS),
        reasoning_effort=max((options['reasoning_effort'],'high'),key=effort_rank.get),
        evidence_steps=max(options['evidence_steps'] or 0,QUALITY_EVIDENCE_STEPS),
        evidence_tools=max(options['evidence_tools'] or 0,QUALITY_EVIDENCE_TOOLS),
        retrieval_max_tokens=max(options['retrieval_max_tokens'] or 0,QUALITY_RETRIEVAL_MAX_TOKENS),
        retrieval_reasoning_effort=options['retrieval_reasoning_effort'] or 'low')
    return options
