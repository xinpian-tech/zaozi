"""Test-only construction of complete UT fixtures; never used by generation or RAG."""
import sequence_framework as framework


def full_ut(label, expression, design=None, *, module=None):
    return ut_with_goals([(label, expression)], design, module=module or "Test_" + label)


def append_goals(ut, goals):
    for label, expression in goals:
        ut["generationLabels"].append(label)
        ut["source"] += f'''    Gen((
{chr(10).join('      ' + line for line in expression.splitlines())}
    ), "{label}")
'''
    return ut


def ut_with_goals(goals, design=None, *, module="TestUT"):
    design = design or framework.load_design(framework.ROOT / "experiments/tests/fixtures/tiny_design.json")
    connections = [f"    dut.io.`{design.clock}` := io.clock",
                   f"    dut.io.`{design.reset}` := {'!' if design.reset_active_low else ''}io.reset.asBool"]
    for port in design.data_ports:
        connections.append(f"    dut.io.`{port.name}` := io.`{port.name}`" if port.direction == "input" else
                           f"    io.`{port.name}` := dut.io.`{port.name}`")
    source = framework.IMPORTS + f'''
@generator
object {module} extends Generator[RunParameter, RunLayers, RunIO, RunProbe] with UT[RunParameter, RunIO]:
  override def moduleName(parameter: RunParameter): String = "{module}"
  def architecture(parameter: RunParameter) =
    val io = summon[Interface[RunIO]]
    val dut = ImportedDut.instantiate(parameter)
{chr(10).join(connections)}
    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
'''
    return append_goals({"module": module, "generationLabels": [], "source": source}, goals)
