# Compiles the C controllers into FMI 2.0 FMU files, using Zig's C compiler.
# Usage: powershell -ExecutionPolicy Bypass -File .\fmus\build.ps1
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$src = Join-Path $here "src"

function Build-Fmu {
    param([string]$Output, [string]$Source, [string]$Model, [string]$Description, [string[]]$Inputs, [string[]]$Outputs, [string[]]$Defines = @())

    $identifier = "TwinGuard" + (($Output -replace '[^A-Za-z0-9]', '_'))
    $guid = "twinguard-$Output"
    $work = Join-Path ([IO.Path]::GetTempPath()) ("fmu-" + [guid]::NewGuid())
    $bin = Join-Path $work "binaries\win64"
    New-Item -ItemType Directory -Force $bin | Out-Null

    $reference = 0
    $variables = foreach ($name in $Inputs) {
        "    <ScalarVariable name=`"$name`" valueReference=`"$reference`" causality=`"input`" variability=`"continuous`"><Real start=`"0`"/></ScalarVariable>"
        $reference++
    }
    $variables += foreach ($name in $Outputs) {
        "    <ScalarVariable name=`"$name`" valueReference=`"$reference`" causality=`"output`" variability=`"continuous`"><Real/></ScalarVariable>"
        $reference++
    }
    $outputIndexes = (1..$Outputs.Count | ForEach-Object { "      <Unknown index=`"$($Inputs.Count + $_)`"/>" }) -join "`n"
    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<fmiModelDescription fmiVersion="2.0" modelName="$Model" guid="$guid" description="$Description" generationTool="zig cc" variableNamingConvention="flat">
  <CoSimulation modelIdentifier="$identifier" canHandleVariableCommunicationStepSize="true"/>
  <ModelVariables>
$($variables -join "`n")
  </ModelVariables>
  <ModelStructure>
    <Outputs>
$outputIndexes
    </Outputs>
  </ModelStructure>
</fmiModelDescription>
"@
    [IO.File]::WriteAllText((Join-Path $work "modelDescription.xml"), $xml, (New-Object Text.UTF8Encoding($false)))

    # The ID goes in a generated header, because shells strip the quotes from a -D value.
    $guidHeader = Join-Path $work "fmu_guid.h"
    [IO.File]::WriteAllText($guidHeader, "#define FMU_GUID `"$guid`"`n", (New-Object Text.UTF8Encoding($false)))
    $flags = @($Defines | ForEach-Object { "-D$_" })
    & zig cc -target x86_64-windows-gnu -shared -O2 -include $guidHeader -I $src @flags -o (Join-Path $bin "$identifier.dll") (Join-Path $src $Source)
    if ($LASTEXITCODE) { throw "Compiling $Output failed" }
    Get-ChildItem $bin -Exclude "*.dll" | Remove-Item -Force -Confirm:$false

    $fmu = Join-Path $here "$Output.fmu"
    if (Test-Path $fmu) { Remove-Item $fmu -Force -Confirm:$false }
    Push-Location $work
    # Windows tar writes zip entries with forward slashes, as the FMI standard expects.
    Remove-Item $guidHeader -Force -Confirm:$false
    # It picks the format from the extension, so the file is named .zip first.
    $zip = Join-Path $here "$Output.zip"
    & tar.exe -a -c -f $zip modelDescription.xml binaries
    if ($LASTEXITCODE) { throw "Packaging $Output failed" }
    Move-Item $zip $fmu
    Pop-Location
    Remove-Item $work -Recurse -Force -Confirm:$false
    Write-Host "Built $Output.fmu"
}

$brakeInputs = "sensor_new", "target_seen", "measured_distance", "sensor_tick", "tick", "speed", "friction"
$brakeOutputs = @("brake_level")
Build-Fmu "brake_v1_supplier" "brake_controller.c" "Supplier braking v1" "Approved braking software, written in C" $brakeInputs $brakeOutputs
Build-Fmu "brake_v2_margin_bug" "brake_controller.c" "Supplier braking v2" "Braking update with a reduced stopping margin" $brakeInputs $brakeOutputs @("STOP_MARGIN=0.9")
Build-Fmu "brake_v2_radar_bug" "brake_controller.c" "Supplier braking v2 radar" "Braking update with radar filtering removed" $brakeInputs $brakeOutputs @("DEBOUNCE_CYCLES=1", "FILTER_WINDOW=1")

$batteryInputs = "cell_voltage", "cell_temperature", "requested_current"
$batteryOutputs = "current_limit", "contactor_closed"
Build-Fmu "battery_v1_supplier" "battery_controller.c" "Supplier battery v1" "Approved battery software, written in C" $batteryInputs $batteryOutputs
Build-Fmu "battery_v2_hot_bug" "battery_controller.c" "Supplier battery v2" "Battery update with raised temperature limits" $batteryInputs $batteryOutputs @("DERATE_TEMPERATURE=52.0", "CUTOFF_TEMPERATURE=64.0")
