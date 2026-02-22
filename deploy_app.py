#!/usr/bin/env python3
"""
Hubitat App/Driver Deployment Script for Frigate Integration
Automates updating app and driver code in Hubitat's web editor
Supports auto-discovery of editor IDs from Hubitat list pages
"""

import sys
import os
import re
from pathlib import Path

try:
    from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError
except ImportError:
    print("ERROR: playwright not installed. Installing...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "playwright", "--quiet"])
    subprocess.check_call([sys.executable, "-m", "playwright", "install", "chromium", "--with-deps"])
    from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

# Default hub IP for C8-2 (can be overridden)
DEFAULT_HUB_IP = "192.168.2.222"  # HubitatC8-2

def find_editor_id(page, hub_ip, name, component_type="app"):
    """
    Auto-discover editor ID by searching the list page
    
    Args:
        page: Playwright page object
        hub_ip: Hubitat hub IP address
        name: Name of the app/driver to search for
        component_type: "app" or "driver"
    
    Returns:
        Editor ID as string, or None if not found
    """
    try:
        list_url = f"http://{hub_ip}/{component_type}/list"
        print(f"Searching for '{name}' in {component_type} list: {list_url}")
        page.goto(list_url, wait_until="domcontentloaded", timeout=30000)
        page.wait_for_timeout(2000)
        
        # Check for login
        login_inputs = page.query_selector_all("input[type='password']")
        if login_inputs:
            print("⚠️  Login required - waiting 10 seconds for manual login...")
            page.wait_for_timeout(10000)
        
        content = page.content()
        
        # Look for links to editor pages matching the name
        # Pattern: /app/editor/123 or /driver/editor/123
        pattern = rf'/{component_type}/editor/(\d+)'
        matches = re.finditer(pattern, content)
        
        for match in matches:
            editor_id = match.group(1)
            # Check if name appears near this editor link
            start_pos = max(0, match.start() - 200)
            end_pos = min(len(content), match.end() + 200)
            context = content[start_pos:end_pos]
            
            if name.lower() in context.lower():
                print(f"✓ Found {component_type} editor ID: {editor_id}")
                return editor_id
        
        # Alternative: search all links/table rows
        elements = page.query_selector_all("a, tr, td")
        for elem in elements:
            text = elem.inner_text() if hasattr(elem, 'inner_text') else ''
            href = elem.get_attribute('href') or ''
            
            if name.lower() in text.lower() and f'/{component_type}/editor/' in href:
                match = re.search(r'/editor/(\d+)', href)
                if match:
                    editor_id = match.group(1)
                    print(f"✓ Found {component_type} editor ID: {editor_id}")
                    return editor_id
        
        print(f"⚠️  Could not find editor ID for '{name}'")
        return None
    except Exception as e:
        print(f"Error finding {component_type} editor ID: {e}")
        return None

def find_app_editor_id(page, hub_ip, app_name):
    """Convenience wrapper for app discovery"""
    return find_editor_id(page, hub_ip, app_name, "app")

def find_driver_editor_id(page, hub_ip, driver_name):
    """Convenience wrapper for driver discovery"""
    return find_editor_id(page, hub_ip, driver_name, "driver")

def deploy_app(app_url, app_code_path, headless=False):
    """
    Deploy app code to Hubitat editor
    
    Args:
        app_url: URL to Hubitat app editor (e.g., http://192.168.2.222/app/editor/1212)
        app_code_path: Path to the .groovy file
        headless: Run browser in headless mode
    """
    app_code_path = Path(app_code_path)
    
    if not app_code_path.exists():
        print(f"ERROR: App code file not found: {app_code_path}")
        return False
    
    # Read the app code
    with open(app_code_path, 'r', encoding='utf-8') as f:
        app_code = f.read()
    
    print(f"Loaded app code from: {app_code_path}")
    print(f"Code length: {len(app_code)} characters")
    
    with sync_playwright() as p:
        print("Launching browser...")
        browser = p.chromium.launch(headless=headless)
        context = browser.new_context()
        page = context.new_page()
        
        try:
            print(f"Navigating to: {app_url}")
            page.goto(app_url, wait_until="domcontentloaded", timeout=30000)
            
            # Wait a bit for page to fully load
            page.wait_for_timeout(3000)
            
            # Check if we need to login (look for login form)
            login_inputs = page.query_selector_all("input[type='password'], input[name*='password'], input[name*='Password']")
            if login_inputs:
                print("⚠️  Login page detected. Please enter credentials manually if needed.")
                print("Waiting 10 seconds for manual login...")
                page.wait_for_timeout(10000)
            
            # Wait for the code editor to be visible
            print("Waiting for CodeMirror editor to load...")
            
            # Hubitat uses CodeMirror - wait for it to be ready
            try:
                # Wait for CodeMirror container
                page.wait_for_selector(".CodeMirror", timeout=15000)
                print("✓ CodeMirror editor found")
                
                # Wait a bit more for CodeMirror to fully initialize
                page.wait_for_timeout(2000)
                
                # Update code using CodeMirror API
                print("Updating code in CodeMirror editor...")
                
                # Use JavaScript to access CodeMirror instance and set value
                # CodeMirror instances are typically stored on the element
                result = page.evaluate(f"""
                    (function() {{
                        // Find CodeMirror editor
                        var cmElement = document.querySelector('.CodeMirror');
                        if (!cmElement) {{
                            return {{success: false, error: 'CodeMirror element not found'}};
                        }}
                        
                        // Get CodeMirror instance - it might be stored in different ways
                        var cm = null;
                        if (cmElement.CodeMirror) {{
                            cm = cmElement.CodeMirror;
                        }} else if (window.CodeMirror && cmElement.cm) {{
                            cm = cmElement.cm;
                        }} else {{
                            // Try to get from CodeMirror's internal structure
                            var editors = document.querySelectorAll('.CodeMirror');
                            for (var i = 0; i < editors.length; i++) {{
                                if (editors[i].CodeMirror) {{
                                    cm = editors[i].CodeMirror;
                                    break;
                                }}
                            }}
                        }}
                        
                        if (!cm) {{
                            // Fallback: try to find the textarea and update it directly
                            var textarea = document.querySelector('textarea');
                            if (textarea) {{
                                textarea.value = arguments[0];
                                // Trigger input event to notify CodeMirror
                                var event = new Event('input', {{ bubbles: true }});
                                textarea.dispatchEvent(event);
                                // Also trigger change event
                                var changeEvent = new Event('change', {{ bubbles: true }});
                                textarea.dispatchEvent(changeEvent);
                                return {{success: true, method: 'textarea'}};
                            }}
                            return {{success: false, error: 'CodeMirror instance not found'}};
                        }}
                        
                        // Set the value using CodeMirror API
                        cm.setValue(arguments[0]);
                        
                        // Trigger change event to mark code as modified (enables Save button)
                        // CodeMirror's change event
                        if (cm.trigger) {{
                            cm.trigger('change');
                        }}
                        
                        // Also trigger via the textarea to ensure Hubitat detects the change
                        var textarea = cm.getTextArea();
                        if (textarea) {{
                            // Trigger multiple events to ensure Hubitat detects modification
                            var events = ['input', 'change', 'keyup'];
                            events.forEach(function(eventType) {{
                                var event = new Event(eventType, {{ bubbles: true }});
                                textarea.dispatchEvent(event);
                            }});
                        }}
                        
                        // Force a focus/blur cycle to trigger change detection
                        if (cm.hasFocus && cm.hasFocus()) {{
                            cm.getInputField().blur();
                            setTimeout(function() {{ cm.getInputField().focus(); }}, 100);
                        }}
                        
                        return {{success: true, method: 'codemirror'}};
                    }})
                """, app_code)
                
                if result.get('success'):
                    print(f"✓ Code updated successfully using {result.get('method', 'unknown')} method")
                else:
                    print(f"⚠️  Warning: {result.get('error', 'Unknown error')}")
                    # Try fallback method
                    textarea = page.query_selector("textarea")
                    if textarea:
                        print("Trying fallback: direct textarea update...")
                        textarea.fill(app_code)
                        print("✓ Code updated via textarea fallback")
                    else:
                        print("❌ Could not update code - no editor found")
                        return False
                        
            except PlaywrightTimeoutError:
                print("⚠️  CodeMirror not found, trying alternative methods...")
                # Take a screenshot for debugging
                screenshot_path = "/tmp/hubitat_editor_screenshot.png"
                page.screenshot(path=screenshot_path)
                print(f"Screenshot saved to: {screenshot_path}")
                
                # Try direct textarea approach
                textarea = page.query_selector("textarea")
                if textarea:
                    print("Found textarea, updating directly...")
                    textarea.fill(app_code)
                    print("✓ Code updated via textarea")
                else:
                    print("❌ No editor found")
                    return False
                
            # Look for Save button and click it
            print("\nLooking for Save button...")
            save_button = None
            
            # Wait a moment for UI to update after code change
            page.wait_for_timeout(1000)
            
            # Try different possible selectors for Save button
            # Hubitat typically uses buttons with "Save" text or specific classes
            save_selectors = [
                "button:has-text('Save')",
                "input[value='Save']",
                "button[type='submit']:has-text('Save')",
                ".btn-primary:has-text('Save')",
                "a:has-text('Save')",
                "button.btn-primary",
                "input.btn-primary[type='submit']",
                "[onclick*='save']",
                "[onclick*='Save']"
            ]
            
            for selector in save_selectors:
                try:
                    elements = page.query_selector_all(selector)
                    for elem in elements:
                        text = elem.inner_text() if hasattr(elem, 'inner_text') else elem.get_attribute('value') or ''
                        if 'save' in text.lower() or elem.get_attribute('onclick') and 'save' in elem.get_attribute('onclick').lower():
                            save_button = elem
                            print(f"✓ Found Save button with selector: {selector}")
                            break
                    if save_button:
                        break
                except Exception as e:
                    continue
            
            if save_button:
                print("Waiting for Save button to be enabled...")
                # Wait for button to be enabled (Hubitat may need a moment to register code changes)
                try:
                    page.wait_for_timeout(2000)  # Give Hubitat a moment to register changes
                    # Try waiting for enabled state with a longer timeout
                    save_button.wait_for_element_state("enabled", timeout=10000)
                    print("Save button is enabled")
                except Exception as e:
                    # If still not enabled, try clicking via JavaScript (bypasses enabled check)
                    print(f"Save button not enabled after wait, attempting JavaScript click...")
                    try:
                        save_button.evaluate("el => el.click()")
                        print("Clicked Save button via JavaScript")
                    except Exception as js_error:
                        print(f"JavaScript click also failed: {js_error}")
                        raise e
                else:
                    # Button is enabled, use normal click
                    print("Clicking Save button...")
                    save_button.scroll_into_view_if_needed()
                    save_button.click()
                
                # Wait for save to process and page to update
                print("Waiting for save to complete...")
                page.wait_for_timeout(3000)
                
                # Check for compilation errors
                print("Checking for compilation errors...")
                
                # Hubitat displays errors in various formats:
                # - "expecting 'X', found 'Y' @ line N, column M"
                # - "unexpected token X @ line N"
                # - "syntax error: X @ line N"
                # - Generic error messages
                errors_found = []
                
                # Wait a moment for error messages to appear after save
                page.wait_for_timeout(1500)
                
                # Method 1: Check for yellow warning banners (Hubitat's primary error display)
                # Look for elements with warning/alert classes, especially yellow ones
                warning_selectors = [
                    ".alert-warning",
                    ".alert-danger", 
                    ".warning",
                    "[class*='warning']",
                    "[class*='alert']",
                    "[class*='error']",
                    "[class*='compile']",
                    "[id*='error']",
                    "[id*='warning']",
                    "[style*='yellow']",
                    "[style*='#ffc107']",  # Bootstrap warning yellow
                    "[style*='#ffeb3b']",  # Material yellow
                    "[style*='#f0ad4e']",  # Bootstrap warning
                ]
                
                for selector in warning_selectors:
                    try:
                        elements = page.query_selector_all(selector)
                        for elem in elements:
                            text = elem.inner_text()
                            if text and text.strip():
                                text_lower = text.lower()
                                # Look for compilation error patterns - be more inclusive
                                # Any text in a warning/error element is likely an error
                                if any(pattern in text_lower for pattern in [
                                    'expecting', 'found', '@ line', 'line ', 'column',
                                    'syntax error', 'compilation error', 'parse error',
                                    'unexpected', 'token', 'cannot', 'failed',
                                    'error', 'exception', 'invalid'
                                ]):
                                    # Clean up the text (remove extra whitespace)
                                    clean_text = ' '.join(text.split())
                                    if clean_text not in errors_found:
                                        errors_found.append(clean_text)
                    except Exception as e:
                        continue
                
                # Method 2: Check all visible text for error patterns
                # Hubitat shows errors prominently, so check page content
                try:
                    # Get all text content from the page
                    all_text = page.evaluate("""
                        () => {
                            // Get text from all elements, prioritizing visible ones
                            const walker = document.createTreeWalker(
                                document.body,
                                NodeFilter.SHOW_TEXT,
                                null,
                                false
                            );
                            let text = '';
                            let node;
                            while (node = walker.nextNode()) {
                                const parent = node.parentElement;
                                // Skip script and style tags
                                if (parent && parent.tagName !== 'SCRIPT' && parent.tagName !== 'STYLE') {
                                    // Check if element is visible
                                    const style = window.getComputedStyle(parent);
                                    if (style.display !== 'none' && style.visibility !== 'hidden') {
                                        text += node.textContent + '\\n';
                                    }
                                }
                            }
                            return text;
                        }
                    """)
                    
                    if all_text:
                        # Look for various error patterns in the text
                        lines = all_text.split('\n')
                        for line in lines:
                            line_lower = line.lower().strip()
                            # Check for multiple error formats:
                            # - "expecting X, found Y @ line N"
                            # - "unexpected token X @ line N"
                            # - "syntax error @ line N"
                            # - "error @ line N"
                            # - Any line with "@ line" and error keywords
                            if '@ line' in line_lower or 'line ' in line_lower:
                                # Check if it contains error indicators
                                if any(keyword in line_lower for keyword in [
                                    'expecting', 'found', 'unexpected', 'token',
                                    'syntax', 'error', 'exception', 'cannot',
                                    'invalid', 'failed', 'parse'
                                ]):
                                    clean_line = ' '.join(line.split())
                                    if clean_line and clean_line not in errors_found:
                                        errors_found.append(clean_line)
                except Exception as e:
                    pass
                
                # Check for "modified" indicator in red (indicates unsaved/error state)
                try:
                    modified_indicators = page.query_selector_all("[class*='modified'], [class*='error'], [style*='red']")
                    for elem in modified_indicators:
                        text = elem.inner_text()
                        if text and ('modified' in text.lower() or 'error' in text.lower()):
                            # Check if there's an actual error message nearby
                            parent = elem.evaluate_handle("el => el.parentElement")
                            if parent:
                                parent_text = parent.inner_text() if hasattr(parent, 'inner_text') else ''
                                if 'expecting' in parent_text.lower() or 'found' in parent_text.lower():
                                    errors_found.append(parent_text.strip())
                except:
                    pass
                
                # Method 3: Use regex to find various error patterns
                try:
                    # Check for multiple error patterns using regex
                    error_patterns = page.evaluate("""
                        () => {
                            const bodyText = document.body.innerText;
                            const patterns = [];
                            
                            // Pattern 1: "expecting 'X', found 'Y' @ line N, column M"
                            const pattern1 = /expecting\\s+['"]([^'"]+)['"]\\s*,\\s*found\\s+['"]([^'"]+)['"]\\s*@\\s*line\\s+(\\d+)(?:\\s*,\\s*column\\s+(\\d+))?/gi;
                            let match;
                            while ((match = pattern1.exec(bodyText)) !== null) {
                                patterns.push(match[0]);
                            }
                            
                            // Pattern 2: "unexpected token X @ line N"
                            const pattern2 = /unexpected\\s+token\\s+['"]?([^'"]+)['"]?\\s*@\\s*line\\s+(\\d+)/gi;
                            while ((match = pattern2.exec(bodyText)) !== null) {
                                patterns.push(match[0]);
                            }
                            
                            // Pattern 3: "syntax error @ line N"
                            const pattern3 = /syntax\\s+error[^@]*@\\s*line\\s+(\\d+)/gi;
                            while ((match = pattern3.exec(bodyText)) !== null) {
                                patterns.push(match[0]);
                            }
                            
                            // Pattern 4: Any error message with line number
                            const pattern4 = /(?:error|exception|invalid|failed|cannot)[^@]*@\\s*line\\s+(\\d+)/gi;
                            while ((match = pattern4.exec(bodyText)) !== null) {
                                // Only add if it's substantial (more than just "error @ line N")
                                if (match[0].length > 15) {
                                    patterns.push(match[0]);
                                }
                            }
                            
                            return patterns;
                        }
                    """)
                    
                    if error_patterns:
                        for pattern in error_patterns:
                            if pattern and pattern not in errors_found:
                                errors_found.append(pattern.strip())
                except Exception as e:
                    pass
                
                # Method 4: Check if page shows "modified" in red (indicates error state)
                try:
                    # Look for red text or "modified" indicators that suggest errors
                    modified_elements = page.query_selector_all("[style*='red'], [class*='modified'], [class*='error']")
                    for elem in modified_elements:
                        # Check if there's error text nearby
                        parent = elem.evaluate_handle("el => el.closest('div, span, p, td, th')")
                        if parent:
                            try:
                                parent_text = parent.inner_text()
                                if parent_text and any(keyword in parent_text.lower() for keyword in [
                                    'expecting', 'found', '@ line', 'error', 'exception'
                                ]):
                                    clean_text = ' '.join(parent_text.split())
                                    if clean_text and clean_text not in errors_found:
                                        errors_found.append(clean_text[:200])  # Limit length
                            except:
                                pass
                except:
                    pass
                
                # Check if yellow warning banner is present (indicates error)
                # On successful save, the yellow banner does NOT appear
                yellow_banner_present = False
                try:
                    # Look specifically for yellow warning banners
                    yellow_selectors = [
                        ".alert-warning",
                        "[class*='warning']",
                        "[style*='yellow']",
                        "[style*='#ffc107']",
                        "[style*='#ffeb3b']",
                    ]
                    for selector in yellow_selectors:
                        elements = page.query_selector_all(selector)
                        if elements:
                            # Check if any contain error text
                            for elem in elements:
                                text = elem.inner_text()
                                if text and any(keyword in text.lower() for keyword in [
                                    'expecting', 'found', '@ line', 'error', 'exception'
                                ]):
                                    yellow_banner_present = True
                                    break
                        if yellow_banner_present:
                            break
                except:
                    pass
                
                # Deduplicate errors
                unique_errors = list(set(errors_found))
                
                if unique_errors or yellow_banner_present:
                    print("\n❌ COMPILATION ERRORS FOUND:")
                    if yellow_banner_present:
                        print("  - Yellow warning banner detected (indicates compilation error)")
                    for error in unique_errors:
                        # Truncate very long errors but keep important parts
                        if len(error) > 300:
                            # Keep first 200 chars and last 100 chars
                            display_error = error[:200] + "..." + error[-100:]
                        else:
                            display_error = error
                        print(f"  - {display_error}")
                    return False
                else:
                    # No errors found AND no yellow banner = successful save
                    print("✅ No compilation errors detected. Code saved successfully!")
                    print("   (No yellow warning banner present - indicates successful compilation)")
                    return True
            else:
                print("⚠️  Save button not found. Code updated but not saved.")
                print("Please manually save the code in the browser.")
                # Take screenshot to help debug
                screenshot_path = "/tmp/hubitat_no_save_button.png"
                page.screenshot(path=screenshot_path)
                print(f"Screenshot saved to: {screenshot_path}")
                return False
                
        except Exception as e:
            print(f"ERROR: {e}")
            import traceback
            traceback.print_exc()
            return False
        finally:
            browser.close()
            # Return focus to Cursor so user can see completion status
            import subprocess
            subprocess.run(['osascript', '-e', 'tell application "Cursor" to activate'])

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 deploy_app.py <code_path> [editor_url] [--headless] [--auto] [--hub-ip <ip>]")
        print("\nOptions:")
        print("  code_path       Path to the .groovy file (app or driver) (required)")
        print("  editor_url      Full editor URL (optional if using --auto)")
        print("  --auto          Auto-discover editor ID from name in code (works for apps and drivers)")
        print("  --hub-ip <ip>   Hubitat hub IP (default: 192.168.2.222 for C8-2)")
        print("  --headless      Run browser in headless mode")
        print("\nExamples:")
        print("  # Auto-discover App:")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy' --auto")
        print("\n  # Auto-discover Driver:")
        print("  python3 deploy_app.py 'Frigate Camera Device.groovy' --auto")
        print("  python3 deploy_app.py 'Frigate MQTT Bridge Device.groovy' --auto")
        print("\n  # With explicit URL:")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy' http://192.168.2.222/app/editor/1212")
        print("\n  # Deploy all components:")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy' --auto")
        print("  python3 deploy_app.py 'Frigate Camera Device.groovy' --auto")
        print("  python3 deploy_app.py 'Frigate MQTT Bridge Device.groovy' --auto")
        sys.exit(1)
    
    # Parse arguments
    app_code_path = None
    app_url = None
    headless = False
    auto_discover = False
    hub_ip = DEFAULT_HUB_IP
    
    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg == "--headless":
            headless = True
        elif arg == "--auto":
            auto_discover = True
        elif arg == "--hub-ip" and i + 1 < len(sys.argv):
            hub_ip = sys.argv[i + 1]
            i += 1
        elif arg.startswith("http://") or arg.startswith("https://"):
            app_url = arg
        elif not app_code_path:
            app_code_path = arg
        i += 1
    
    if not app_code_path:
        print("ERROR: app_code_path is required")
        sys.exit(1)
    
    # Auto-discover editor ID if requested
    if auto_discover and not app_url:
        # Extract name from code file and determine if it's an app or driver
        code_path = Path(app_code_path)
        if not code_path.exists():
            print(f"ERROR: Code file not found: {app_code_path}")
            sys.exit(1)
        
        with open(code_path, 'r', encoding='utf-8') as f:
            code = f.read()
        
        # Determine component type (app or driver)
        # Drivers have 'capability' keyword inside metadata block; apps don't
        is_driver = 'capability "' in code or "capability '" in code
        component_type = "driver" if is_driver else "app"
        
        # Try to extract name from definition (app) or metadata (driver)
        name_match = None
        if not is_driver:
            name_match = re.search(r'name:\s*["\']([^"\']+)["\']', code)
        else:
            # For drivers, look for name in definition block (same syntax as apps)
            name_match = re.search(r'name:\s*["\']([^"\']+)["\']', code)
        
        if name_match:
            component_name = name_match.group(1)
        else:
            # Fallback: use filename
            component_name = code_path.stem.replace("-", " ").replace("_", " ")
        
        print(f"Auto-discovering {component_type} editor ID for: {component_name}")
        print(f"Hub IP: {hub_ip}")
        
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=False)  # Always visible for discovery
            context = browser.new_context()
            page = context.new_page()
            
            try:
                editor_id = find_editor_id(page, hub_ip, component_name, component_type)
                if editor_id:
                    app_url = f"http://{hub_ip}/{component_type}/editor/{editor_id}"
                    print(f"Using editor URL: {app_url}")
                else:
                    print(f"ERROR: Could not auto-discover {component_type} editor ID")
                    print("Please provide the editor URL manually or check the name")
                    browser.close()
                    # Return focus to Cursor so user can see completion status
                    import subprocess
                    subprocess.run(['osascript', '-e', 'tell application "Cursor" to activate'])
                    sys.exit(1)
            except Exception as e:
                print(f"ERROR during auto-discovery: {e}")
                browser.close()
                # Return focus to Cursor so user can see completion status
                import subprocess
                subprocess.run(['osascript', '-e', 'tell application "Cursor" to activate'])
                sys.exit(1)
            finally:
                browser.close()
                # Return focus to Cursor so user can see completion status
                import subprocess
                subprocess.run(['osascript', '-e', 'tell application "Cursor" to activate'])
    
    if not app_url:
        print("ERROR: app_url is required (provide URL or use --auto)")
        sys.exit(1)
    
    success = deploy_app(app_url, app_code_path, headless=headless)
    sys.exit(0 if success else 1)
