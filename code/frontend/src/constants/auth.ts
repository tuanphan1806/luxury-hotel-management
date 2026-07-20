// ─── Brand ───
export const BRAND_NAME = 'Luxury Hotel';
export const BRAND_TAGLINE = 'DIRECT BOOKING';
export const BADGE_ESTABLISHED = '⚜ ESTABLISHED SINCE 1925';

// ─── Login Hero ───
export const HERO_TITLE = 'Excellence is Our Standard';
export const HERO_SUBTITLE = 'Manage every detail of your property with the precision and elegance your guests deserve.';

export const STAT_1_NUM = '248';
export const STAT_1_LABEL = 'Rooms & Suites';
export const STAT_2_NUM = '98%';
export const STAT_2_LABEL = 'Guest Satisfaction';
export const STAT_3_NUM = '100+';
export const STAT_3_LABEL = 'Years of Luxury';

// ─── Signup Hero ───
export const SIGNUP_HERO_TITLE = 'Your Luxury Experience Awaits';
export const SIGNUP_HERO_SUBTITLE = 'Create an account to book rooms, manage reservations, and enjoy exclusive member benefits.';
export const SIGNUP_FEATURES = [
  'Exclusive member rates & early access',
  'Seamless booking & reservation management',
  'Personalized stay preferences',
  'Loyalty rewards & special offers',
];

// ─── Form Titles ───
export const FORM_TITLE_LOGIN = 'Welcome back';
export const FORM_SUBTITLE_LOGIN = 'Sign in to access your management portal.';
export const FORM_TITLE_SIGNUP = 'Create your account';
export const FORM_SUBTITLE_SIGNUP = 'Join Luxury Hotels and unlock exclusive benefits.';

// ─── Social ───
export const SOCIAL_GOOGLE = 'Continue with Google';
export const SOCIAL_FACEBOOK = 'Continue with Facebook';
export const DIVIDER_TEXT = 'or sign in with email';
export const DIVIDER_TEXT_SIGNUP = 'or register with email';

// ─── Login Form ───
export const LABEL_EMAIL = 'Email Address';
export const PLACEHOLDER_EMAIL = 'manager@luxuryhotels.com';
export const LABEL_USERNAME = 'Username';
export const PLACEHOLDER_USERNAME = 'alex99';
export const LABEL_LOGIN_IDENTIFIER = 'Username or Email';
export const LABEL_PASSWORD = 'Password';
export const PLACEHOLDER_PASSWORD = '••••••••••';
export const LINK_FORGOT = 'Forgot password?';
export const LINK_SIGNUP = 'Create account';
export const TEXT_SIGNUP_PROMPT = "Don't have an account? ";

// ─── Signup Form ───
export const LABEL_FULL_NAME = 'Full Name';
export const PLACEHOLDER_FULL_NAME = 'Alexandre Martin';
export const LABEL_CONFIRM_PASSWORD = 'Confirm Password';

// ─── Buttons ───
export const BTN_SIGNIN = 'Sign In';
export const BTN_SIGNING = 'Signing in...';
export const BTN_CONTINUE = 'Continue';
export const BTN_BACK = 'Back';
export const BTN_REGISTER = 'Create Account';
export const BTN_REGISTERING = 'Creating account...';

// ─── Links ───
export const LINK_LOGIN = 'Sign in';
export const TEXT_LOGIN_PROMPT = 'Already have an account? ';

// ─── Errors (signup)───
export const ERROR_REQUIRED = 'Please fill in all required fields.';
export const ERROR_INVALID_FULL_NAME = 'Invalid Full Name. It must be between 2 to 50 characters and contain letters only.';
export const ERROR_EMAIL_DOMAIN = 'Access denied. You must use an official @luxuryhotels.com email address.';
export const ERROR_EMAIL_USERNAME_LENGTH = 'Invalid email. The username part before @ must be at least 3 characters long.';
export const ERROR_EMAIL_FORMAT = 'Invalid email format. The username before @ must include both letters and numbers (e.g., thui1, alex9x, it01).';
export const ERROR_PASSWORD_MATCH = 'Passwords do not match.';
export const ERROR_PASSWORD_SPACE = 'Password must not contain any spaces.';
export const ERROR_PASSWORD_STRICT = 'Password must be at least 8 characters, containing ONLY standard English letters (1 uppercase, 1 lowercase), 1 number, and 1 special character. No accents allowed.';
export const ERROR_PASSWORD_LENGTH = 'Password must be at least 6 characters long.'; // Giữ lại dự phòng
export const ERROR_REGISTER_FAILED = 'An error occurred during registration. Please try again.';
// ─── Errors (login)───
export const ERROR_EMAIL_INVALID = 'Please enter a valid email address.'; 
export const ERROR_ACCOUNT_NOT_FOUND = 'No account found with this email address.'; 
export const ERROR_WRONG_PASSWORD = 'The password you entered is incorrect.';

export const DEFAULT_EMAIL = 'manager@luxuryhotels.com';

// ─── Roles (assigned by admin, not user-selectable) ───
export const ROLES = {
  CUSTOMER: 'CUSTOMER',
  STAFF: 'STAFF',
  ADMIN: 'ADMIN',
} as const;

export type UserRole = keyof typeof ROLES;
